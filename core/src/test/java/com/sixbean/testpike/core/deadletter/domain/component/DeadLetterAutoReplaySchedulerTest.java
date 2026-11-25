package com.sixbean.testpike.core.deadletter.domain.component;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.notification.domain.component.NotificationEventDict;
import com.sixbean.testpike.core.sched.domain.component.SchedulerJobCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Auto-replay loop + dead-letter backlog alert for operators.
 * <p>Real dev database, real broker send through the existing replay primitives
 * (zero mocks, strict-testing iron law): the loop must reuse
 * {@link com.sixbean.testpike.core.deadletter.domain.service.DeadLetterReplayService}
 * as its single egress — no second replay gate (R40). Fixture ledger rows are inserted
 * with a run-unique fingerprint and deleted by that prefix afterwards (R37: no TRUNCATE,
 * no shipped-table touches).
 * <p>Coverage of the adjudication obligations: whitelist-only replay
 * (PROVIDER_TRANSIENT / CONFIG_MISSING / LLM_TIMEOUT), the fresh-arrival class gate
 * (a class that dead-lettered inside the interval counts as still-DOWN and gets zero
 * reposts), per-round batch cap, the N-round auto-void with the system operator and a
 * recorded reason, the disabled switch short-circuit, and the registration duties
 * (SchedulerJobCatalog entry, MQ_DLQ_BACKLOG in the notification event dictionary).
 * <p>The cron cadence gate itself (seconds since the last system action) is exposed as
 * a three-state query so a blank result is never reported as "no activity" (P18);
 * the L4 live spectrum (fixture supplier DOWN -> dead-letter -> recover -> replay ->
 * terminal + auto-void) is the companion verification package.
 */
@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = "testpike.scheduling.enabled=true")
class DeadLetterAutoReplaySchedulerTest {

    @Autowired
    private DeadLetterAutoReplayScheduler scheduler;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final String run = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private final String fingerprintPrefix = "dlqautoreplay-" + run;
    private final String queueName = "tp.autoreplay.fixture." + run + ".dlq";
    private final List<Long> fixtureIds = new ArrayList<>();

    @BeforeEach
    void cleanPreviousPartialFixtures() {
        // Same discipline as @AfterEach: a crashed earlier run must not leak rows that the
        // class gate / batch cap would silently count in this one.
        jdbcTemplate.update("DELETE FROM tp_mq_dead_letter WHERE message_fingerprint LIKE ?",
                fingerprintPrefix + "%");
    }

    @AfterEach
    void removeFixtures() {
        jdbcTemplate.update("DELETE FROM tp_mq_dead_letter WHERE message_fingerprint LIKE ?",
                fingerprintPrefix + "%");
        fixtureIds.clear();
    }

    /** Insert one pending ledger row; ageDays>0 backdates created_at, round seeds replay_round. */
    private long insertRow(String failureClass, int ageDays, int round, String status) {
        String fingerprint = fingerprintPrefix + "-" + fixtureIds.size();
        jdbcTemplate.update("INSERT INTO tp_mq_dead_letter (message_fingerprint, queue_name,"
                        + " orig_exchange, orig_routing_key, payload_json, failure_class,"
                        + " failure_reason, status, replay_round, created_at)"
                        + " VALUES (?, ?, '', ?, ?, ?, ?, ?, ?, now() - make_interval(days => ?))",
                fingerprint, queueName, "tp.autoreplay.fixture." + run,
                "{\"fixture\":true,\"run\":\"" + run + "\"}",
                failureClass, "fixture cause chain for T385 unit spectrum",
                status, round, ageDays);
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_mq_dead_letter WHERE message_fingerprint = ?",
                Long.class, fingerprint);
        assertNotNull(id);
        fixtureIds.add(id);
        return id;
    }

    private String statusOf(long id) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM tp_mq_dead_letter WHERE id = ?", String.class, id);
    }

    private DeadLetterAutoReplayPolicy policy(boolean enabled, int intervalSeconds,
                                              int batchLimit, int maxRounds) {
        return new DeadLetterAutoReplayPolicy(enabled, intervalSeconds, batchLimit, maxRounds);
    }

    @Test
    void catalogAndEventDictionaryRegisterTheNewJobAndAlert() {
        // RED anchor: before this package the job catalog had no auto-replay entry
        // and the event dictionary had no MQ_DLQ_BACKLOG.
        assertTrue(new SchedulerJobCatalog().descriptions().containsKey("dlqAutoReplay"),
                "auto-replay job must be listed in the single site-wide SchedulerJobCatalog");
        assertTrue(NotificationEventDict.isKnown("MQ_DLQ_BACKLOG"),
                "backlog alert must be a dictionary event (selectable on the subscription page, single-point filtering)");
        assertTrue(NotificationEventDict.isDefaultEnabled("MQ_DLQ_BACKLOG"),
                "backlog alert subscribes by default (low noise via the threshold key)");
    }

    @Test
    void disabledSwitchRunsZeroReplaysAndZeroVoids() {
        insertRow("LLM_TIMEOUT", 3, 0, "pending");
        int replayed = scheduler.runRound(policy(false, 3600, 50, 3));
        assertEquals(0, replayed);
        Long pending = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_mq_dead_letter WHERE message_fingerprint LIKE ?",
                Long.class, fingerprintPrefix + "%");
        assertEquals(1L, pending, "disabled master switch must not touch any row");
    }

    @Test
    void freshArrivalOfClassGatesThatClassReplayNotOthers() {
        // LLM_TIMEOUT arrived just now = upstream still failing for that signature ->
        // spin without reposts; the old PROVIDER_TRANSIENT row is eligible.
        long freshTimeout = insertRow("LLM_TIMEOUT", 0, 0, "pending");
        long oldTransient = insertRow("PROVIDER_TRANSIENT", 5, 0, "pending");
        int replayed = scheduler.runRound(policy(true, 3600, 50, 3));
        assertEquals(1, replayed, "only the class without a fresh arrival may be reposted");
        assertEquals("replayed", statusOf(oldTransient));
        assertEquals("pending", statusOf(freshTimeout),
                "fresh-arrival class gate: DOWN classes spin, they are never reposted");
    }

    @Test
    void nonWhitelistedClassesAreNeverTouched() {
        long deserialization = insertRow("DESERIALIZATION_FAIL", 5, 0, "pending");
        long unknown = insertRow("UNKNOWN", 5, 0, "pending");
        long entityGone = insertRow("ENTITY_GONE", 5, 0, "pending");
        int replayed = scheduler.runRound(policy(true, 60, 50, 3));
        assertEquals(0, replayed, "whitelist only: other classes never auto-repost (O3-a-1)");
        assertEquals("pending", statusOf(deserialization));
        assertEquals("pending", statusOf(unknown));
        assertEquals("pending", statusOf(entityGone),
                "ENTITY_GONE stays out of the loop on both gates (O3-a-2)");
    }

    @Test
    void batchLimitCapsOneRoundOldestFirst() {
        long oldest = insertRow("CONFIG_MISSING", 9, 0, "pending");
        insertRow("CONFIG_MISSING", 7, 0, "pending");
        long newest = insertRow("CONFIG_MISSING", 5, 0, "pending");
        int replayed = scheduler.runRound(policy(true, 60, 2, 3));
        assertEquals(2, replayed, "single-round cap is a hard bound (anti-replay-storm)");
        assertEquals("replayed", statusOf(oldest), "the cap must consume the oldest rows first");
        assertEquals("pending", statusOf(newest), "the row over the cap stays pending for the next round");
    }

    @Test
    void roundsAtTheLimitVoidAutomaticallyWithSystemOperatorAndReason() {
        long burned = insertRow("PROVIDER_TRANSIENT", 5, 3, "pending");
        long inLoop = insertRow("PROVIDER_TRANSIENT", 5, 1, "pending");
        int replayed = scheduler.runRound(policy(true, 60, 50, 3));
        assertEquals(1, replayed, "a row at the round limit is never reposted again");
        assertEquals("voided", statusOf(burned),
                "N rounds still dead-lettered -> auto voided (replay-storm bound, operator=system)");
        String voidOperator = jdbcTemplate.queryForObject(
                "SELECT operator FROM tp_mq_dead_letter WHERE id = ?", String.class, burned);
        assertEquals(DeadLetterAutoReplayScheduler.AUTO_VOID_OPERATOR, voidOperator);
        String voidReason = jdbcTemplate.queryForObject(
                "SELECT void_reason FROM tp_mq_dead_letter WHERE id = ?", String.class, burned);
        assertNotNull(voidReason);
        assertTrue(voidReason.contains("3"), "the void reason must state the round budget as a readable number");
        assertEquals("replayed", statusOf(inLoop), "rows below the limit still advance normally");
    }

    @Test
    void replayOperatorIsTheSystemMarkerOnAdvancedRows() {
        long id = insertRow("LLM_TIMEOUT", 5, 0, "pending");
        scheduler.runRound(policy(true, 60, 50, 3));
        String operator = jdbcTemplate.queryForObject(
                "SELECT operator FROM tp_mq_dead_letter WHERE id = ?", String.class, id);
        assertEquals(DeadLetterAutoReplayScheduler.AUTO_REPLAY_OPERATOR, operator,
                "each reposted row keeps an operator audit marker (ledger-is-audit, same as the void path)");
        Integer round = jdbcTemplate.queryForObject(
                "SELECT replay_round FROM tp_mq_dead_letter WHERE id = ?", Integer.class, id);
        assertEquals(1, round);
    }

    @Test
    void backlogAlertStaysSilentAtOrUnderThreshold() {
        // Strictly-over semantics pinned here; the over-threshold fan-out + same-window
        // dedup are exercised by the companion L4 live spectrum (a unit run firing real
        // admin letters would stack noise into the shared dev inbox — R37 discipline).
        // Self-provisioned fixtures: a fresh-install ledger starts empty, and the
        // count semantics under test must not lean on rows left by other suites.
        insertRow("LLM_TIMEOUT", 0, 0, "pending");
        insertRow("PROVIDER_TRANSIENT", 0, 0, "pending");
        long pendingGlobal = scheduler.countPendingLedgerRows();
        assertTrue(pendingGlobal >= 2, "fixtures must be visible to the count");
        assertEquals(0, scheduler.checkBacklogAlert((int) pendingGlobal),
                "at threshold: no alert (strictly-over comparison)");
        assertEquals(0, scheduler.checkBacklogAlert((int) pendingGlobal + 1),
                "under threshold: no alert");
    }

    @Test
    void cadenceQueryIsThreeStateNotSilentZero() {
        // P18 posture: the query must not fold "no marker row yet" into a 0 that would
        // read as "just acted" nor throw — null = never ran = first round allowed.
        Long seconds = scheduler.secondsSinceLastSystemActivity();
        assertTrue(seconds == null || seconds >= 0,
                "null = no record (first round allowed); never fold absence into a number");
    }
}
