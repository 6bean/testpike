package com.sixbean.testpike.core.events.domain.component;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L2 contract for the process-event ledger trim sweeper (tail-follow batch,
 * ledger-trimming face).
 * <p>Why this class exists: V460 seeded two hot keys
 * (ai.stream.event-retention-days, ai.stream.max-events-per-task) that no
 * consumer ever read, so tp_ai_task_event was append-only without bound —
 * exactly the unbounded accumulation the resource-lifetime rule forbids.
 * These tests pin the trimming contract: every sweep decision is read fresh
 * from the hot keys (clamp 1~90 days / 100~20000 rows, garbage falls back to
 * the shipped default 7 / 2000, never a sweep-all interpretation), the TTL
 * window deletes only rows past the cutoff, the per-job cap keeps the newest
 * rows and evicts the oldest surplus per (job_kind, job_ref, run_token), and
 * a terminal purge touches exactly the named job and nothing else.
 * <p>Test data posture: a synthetic job_kind "l2-trim" is used so the global
 * TTL sweep can never confuse fixtures with real ledger rows; fixtures are
 * removed by exact predicate in @AfterEach (zero residue). The shared dev
 * slot database may legitimately hold older rows from earlier runs, so the
 * TTL assertions check the fate of the fixture ids themselves instead of any
 * global deleted-count.
 */
@SpringBootTest(classes = CoreApplication.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AiTaskEventTrimSweeperTest {

    /** Synthetic family used only by this test — never a registered job kind. */
    private static final String KIND = "l2-trim";

    @Autowired
    private AiTaskEventTrimSweeper sweeper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ConfigCacheHolder configCache;

    /** Whole-site survey family: rides its own V643 hot keys (never the ai.stream pair). */
    private static final String SITE = AiTaskEventTrimSweeper.KIND_WEB_ELEMENT_SURVEY_SITE;
    private static final String SITE_CAP_KEY = AiTaskEventTrimSweeper.SITE_HISTORY_FRAME_CAP_KEY;
    private static final String SITE_DAYS_KEY = AiTaskEventTrimSweeper.SITE_EVENT_RETENTION_DAYS_KEY;

    private String originalRetention;
    private String originalMaxEvents;
    private String originalSiteCap;
    private String originalSiteDays;

    @BeforeAll
    void captureOriginalKeys() {
        originalRetention = readKey("ai.stream.event-retention-days");
        originalMaxEvents = readKey("ai.stream.max-events-per-task");
        originalSiteCap = readKey(SITE_CAP_KEY);
        originalSiteDays = readKey(SITE_DAYS_KEY);
        assertNotNull(originalRetention, "V460 must have seeded the retention key");
        assertNotNull(originalMaxEvents, "V460 must have seeded the cap key");
        assertNotNull(originalSiteCap, "V643 must have seeded the site family cap key");
        assertNotNull(originalSiteDays, "V643 must have seeded the site family retention key");
    }

    @BeforeEach
    void restoreOriginalKeysBeforeEachCase() {
        restoreSeededKeys();
        deleteFixtures();
    }

    @AfterEach
    void removeFixtures() {
        restoreSeededKeys();
        deleteFixtures();
    }

    private void restoreSeededKeys() {
        writeKey("ai.stream.event-retention-days", originalRetention);
        writeKey("ai.stream.max-events-per-task", originalMaxEvents);
        writeKey(SITE_CAP_KEY, originalSiteCap);
        writeKey(SITE_DAYS_KEY, originalSiteDays);
    }

    /**
     * TTL face: rows older than the hot-key window are removed, fresh rows of
     * the same job and rows of other jobs survive, and the sweep is repeatable.
     */
    @Test
    void trimExpired_removesRowsOlderThanRetentionWindow_only() {
        writeKey("ai.stream.event-retention-days", "1");
        writeKey("ai.stream.max-events-per-task", "20000");
        String ref = "document:l2-exp-" + UUID.randomUUID();
        String token = UUID.randomUUID().toString();
        long oldId1 = insertEvent(ref, token, "step", "里程碑一", 3);
        long oldId2 = insertEvent(ref, token, "thinking", "分片说明", 2);
        long oldId3 = insertEvent(ref, token, "error", "失败原因", 2);
        long freshId = insertEvent(ref, token, "step", "最新进度", 0);
        long survivorId = insertEvent("document:l2-exp-other-" + UUID.randomUUID(),
                token, "step", "其他任务同样在保留期内", 0);

        sweeper.trimExpired();

        assertEquals(0, countById(oldId1) + countById(oldId2) + countById(oldId3),
                "three rows past the 1-day window must be gone");
        assertEquals(1, countById(freshId), "fresh row of the same job stays visible");
        assertEquals(1, countById(survivorId), "other jobs inside the window stay untouched");

        long secondPassOld = insertEvent(ref, token, "step", "又一过期行", 2);
        assertTrue(sweeper.trimExpired() >= 1, "sweep reports the rows it removed");
        assertEquals(0, countById(secondPassOld), "repeat sweep keeps converging (idempotent)");
    }

    /**
     * Cap face: a run that passed the per-job ceiling loses its oldest
     * surplus, keeps the newest rows, and the ceiling applies per job — not
     * globally across kinds or refs.
     */
    @Test
    void trimOverflow_keepsNewestRowsWithinCap_perJob() {
        writeKey("ai.stream.event-retention-days", "90");
        writeKey("ai.stream.max-events-per-task", "100");
        String ref = "document:l2-cap-" + UUID.randomUUID();
        String token = UUID.randomUUID().toString();
        long[] ids = new long[103];
        for (int i = 0; i < 103; i++) {
            ids[i] = insertEvent(ref, token, "step", "步骤 " + i, 0);
        }
        String otherRef = "document:l2-cap-other-" + UUID.randomUUID();
        long smallJobId = insertEvent(otherRef, token, "step", "未超限任务保留", 0);

        int removed = sweeper.trimOverflow();

        assertTrue(removed >= 3, "the three oldest surplus rows must be evicted");
        for (int i = 0; i < 3; i++) {
            assertEquals(0, countById(ids[i]), "oldest row " + i + " evicted first");
        }
        assertEquals(1, countById(ids[3]), "first kept row is exactly the (count-cap) boundary");
        assertEquals(1, countById(ids[102]), "newest row always survives");
        assertEquals(1, countById(smallJobId), "a job below the cap is never trimmed");
        assertEquals(100, countByRef(ref), "kept face is exactly the hot-key cap");
    }

    /**
     * Hot-key resolution face: out-of-range values clamp into the seeded
     * allowed range, unreadable values fall back to the shipped default, and
     * a zero/negative entry never degrades into a sweep-everything reading.
     */
    @Test
    void keyResolution_clampsRange_andFailsClosedOnGarbage() {
        writeKey("ai.stream.event-retention-days", "1000");
        assertEquals(90, sweeper.resolveRetentionDays(), "above max clamps to 90");
        writeKey("ai.stream.event-retention-days", "0");
        assertEquals(1, sweeper.resolveRetentionDays(), "zero clamps up, never sweep-all");
        writeKey("ai.stream.event-retention-days", "-5");
        assertEquals(1, sweeper.resolveRetentionDays(), "negative clamps up");
        writeKey("ai.stream.event-retention-days", "乱码");
        assertEquals(7, sweeper.resolveRetentionDays(), "garbage falls back to shipped 7");

        writeKey("ai.stream.max-events-per-task", "5");
        assertEquals(100, sweeper.resolveMaxEventsPerTask(), "below min clamps to 100");
        writeKey("ai.stream.max-events-per-task", "999999");
        assertEquals(20000, sweeper.resolveMaxEventsPerTask(), "above max clamps to 20000");
        writeKey("ai.stream.max-events-per-task", "not-a-number");
        assertEquals(2000, sweeper.resolveMaxEventsPerTask(), "garbage falls back to shipped 2000");
    }

    /**
     * Terminal purge face: when the owning job row is removed, the trimmer
     * deletes exactly that job's ledger rows by (job_kind, job_ref) and leaves
     * every other job's stream intact (re-run contract: a deleted owner row
     * with zero events is a no-op, not an error).
     */
    @Test
    void purgeJobEvents_removesExactlyTheNamedJob() {
        String refA = "document:l2-purge-a-" + UUID.randomUUID();
        String refB = "document:l2-purge-b-" + UUID.randomUUID();
        String token = UUID.randomUUID().toString();
        insertEvent(refA, token, "step", "任务A事件", 0);
        insertEvent(refA, token, "thinking", "任务A推理", 0);
        insertEvent(refA, token, "error", "任务A失败因", 0);
        long keptId = insertEvent(refB, token, "step", "任务B事件保留", 0);

        int removed = sweeper.purgeJobEvents(KIND, refA);

        assertEquals(3, removed, "every event row of the named job is purged");
        assertEquals(0, countByRef(refA), "purged job has zero rows left");
        assertEquals(1, countById(keptId), "the neighbouring job stream is untouched");
        assertEquals(0, sweeper.purgeJobEvents(KIND, refA), "re-purge of an empty job is a no-op");
    }

    /**
 * Value-range separation contract (R62 segment a): the survey process
 * stream (job_kind=web-element-survey, job_ref=explore session id) and the
 * per-row ledger audit stream (job_kind=element-ledger, job_ref=element
 * row id) are different tables whose ids can numerically collide. A
 * terminal purge addressed by (kind, ref) must therefore remove exactly
 * one stream and leave the colliding-ref stream of the other kind intact
 * — mixing the value ranges would make the cleanup windows step on each
 * other and replays bleed across streams.
 */
    @Test
    void surveyAndRowAuditKindsAreDistinct_andPurgeByKindRefKeepsCollidingRefsApart() {
        assertEquals("web-element-survey", AiTaskEventTrimSweeper.KIND_WEB_ELEMENT_SURVEY,
                "the process-stream kind token is a wire contract with the tail drawer reader");
        assertNotEquals("element-ledger", AiTaskEventTrimSweeper.KIND_WEB_ELEMENT_SURVEY,
                "the two registered streams must be distinct kinds");

        // A colliding numeric ref is the whole point of the drill: same ref,
        // two kinds, two independent streams.
        String ref = String.valueOf(9_000_000_000_000L
                + java.util.concurrent.ThreadLocalRandom.current().nextLong(1_000_000));
        String token = UUID.randomUUID().toString();
        insertEventUnderKind(AiTaskEventTrimSweeper.KIND_WEB_ELEMENT_SURVEY, ref, token,
                "step", "测绘发起回执帧", 0);
        insertEventUnderKind("element-ledger", ref, token, "step", "元素行审计帧", 0);

        int removed = sweeper.purgeJobEvents(AiTaskEventTrimSweeper.KIND_WEB_ELEMENT_SURVEY, ref);

        assertEquals(1, removed, "purging the survey face removes exactly its own frames");
        assertEquals(0, countUnderKind(AiTaskEventTrimSweeper.KIND_WEB_ELEMENT_SURVEY, ref));
        assertEquals(1, countUnderKind("element-ledger", ref),
                "the element-row audit stream under the same numeric ref survives");
        // zero-residue teardown for the surviving fixture row
        assertEquals(1, sweeper.purgeJobEvents("element-ledger", ref));
    }

    /**
 * Family key face ( segment b-ii, closes the R57 reclaim exit of the
 * V643 seeds): the whole-site survey stream resolves its retention days
 * and its history frame cap from its own hot keys, clamped into the
 * seeded ranges 7~730 / 200~20000, garbage falling back to the shipped
 * defaults 90 / 2000 — same fail-closed posture as the global pair.
 */
    @Test
    void siteFamilyKeyResolution_clampsRange_andFailsClosedOnGarbage() {
        assertEquals(90, sweeper.resolveSiteRetentionDays(), "seeded default reads 90");
        assertEquals(2000, sweeper.resolveSiteHistoryFrameCap(), "seeded default reads 2000");

        writeKey(SITE_DAYS_KEY, "5000");
        assertEquals(730, sweeper.resolveSiteRetentionDays(), "above max clamps to 730");
        writeKey(SITE_DAYS_KEY, "0");
        assertEquals(7, sweeper.resolveSiteRetentionDays(), "zero clamps up, never sweep-all");
        writeKey(SITE_DAYS_KEY, "乱码");
        assertEquals(90, sweeper.resolveSiteRetentionDays(), "garbage falls back to shipped 90");

        writeKey(SITE_CAP_KEY, "10");
        assertEquals(200, sweeper.resolveSiteHistoryFrameCap(), "below min clamps to 200");
        writeKey(SITE_CAP_KEY, "999999");
        assertEquals(20000, sweeper.resolveSiteHistoryFrameCap(), "above max clamps to 20000");
        writeKey(SITE_CAP_KEY, "not-a-number");
        assertEquals(2000, sweeper.resolveSiteHistoryFrameCap(), "garbage falls back to shipped 2000");
    }

    /**
     * TTL face per family: a site row inside the family window survives a
     * global window that already killed a same-age row of any other kind, and
     * in the reverse posture a site row past its (shorter) family window is
     * swept while the global window still protects other kinds' rows.
     */
    @Test
    void trimExpired_windowsArePerFamily_siteRowsRideTheirOwnRetention() {
        String token = UUID.randomUUID().toString();
        // Posture one: global window tight (1 day), family window wide (90).
        writeKey("ai.stream.event-retention-days", "1");
        writeKey(SITE_DAYS_KEY, "90");
        writeKey(SITE_CAP_KEY, "20000");
        long siteId = insertEventUnderKind(SITE, siteFixtureRef(), token, "step", "站点帧在自有窗口内", 3);
        long synthId = insertEvent("document:l2-exp-synth-" + UUID.randomUUID(),
                token, "step", "普通帧超全局窗口", 3);
        sweeper.trimExpired();
        assertEquals(1, countById(siteId), "3-day site row stays inside its 90-day family window");
        assertEquals(0, countById(synthId), "same-age row of other kinds follows the global window");

        // Posture two: global window wide (90), family window tight (7).
        writeKey("ai.stream.event-retention-days", "90");
        writeKey(SITE_DAYS_KEY, "7");
        long siteOld = insertEventUnderKind(SITE, siteFixtureRef(), token, "step", "站点帧超自有窗口", 8);
        long synthOld = insertEvent("document:l2-exp-synth2-" + UUID.randomUUID(),
                token, "step", "普通帧在全局窗口内", 8);
        sweeper.trimExpired();
        assertEquals(0, countById(siteOld), "8-day site row leaves at the 7-day family boundary");
        assertEquals(1, countById(synthOld), "global window keeps other kinds inside 90 days");
    }

    /**
     * Ceiling face per family: a site group over the global ceiling but under
     * its own family ceiling survives untouched while a same-size group of
     * another kind is trimmed to the global ceiling; lowering the family
     * ceiling to its floor then bites the site group down to exactly that
     * many newest rows (proof the family cap is really applied, not absent).
     */
    @Test
    void trimOverflow_ceilingsArePerFamily_siteGroupsRideTheirOwnCap() {
        String token = UUID.randomUUID().toString();
        writeKey("ai.stream.event-retention-days", "90");
        writeKey(SITE_DAYS_KEY, "90");
        writeKey("ai.stream.max-events-per-task", "100");
        writeKey(SITE_CAP_KEY, "200");
        String siteRef = siteFixtureRef();
        String synthRef = "document:l2-cap-synth-" + UUID.randomUUID();
        for (int i = 0; i < 103; i++) {
            insertEventUnderKind(SITE, siteRef, token, "step", "站点帧 " + i, 0);
            insertEvent(synthRef, token, "step", "普通帧 " + i, 0);
        }
        sweeper.trimOverflow();
        assertEquals(103, countUnderKind(SITE, siteRef),
                "103 site rows sit under the 200-row family ceiling and must survive the 100-row global one");
        assertEquals(100, countByRef(synthRef), "other kinds keep the global ceiling");

        writeKey(SITE_CAP_KEY, "200");
        for (int i = 103; i < 205; i++) {
            insertEventUnderKind(SITE, siteRef, token, "step", "站点帧 " + i, 0);
        }
        sweeper.trimOverflow();
        assertEquals(200, countUnderKind(SITE, siteRef),
                "family ceiling bites at its floor: 205 rows trimmed to the newest 200");
    }

    /** Fixture refs of the site family carry a distinct prefix so teardown can never hit real runs. */
    private String siteFixtureRef() {
        return "l2-site-" + UUID.randomUUID();
    }

    // ── fixtures ──────────────────────────────────────────────────────────

    /** Insert one ledger row; daysBack>0 backdates created_at like real aging. */
    private long insertEvent(String ref, String token, String level, String summary, int daysBack) {
        return insertEventUnderKind(KIND, ref, token, level, summary, daysBack);
    }

    /** Insert under an explicit registered kind (stream-separation drills). */
    private long insertEventUnderKind(String kind, String ref, String token,
                                      String level, String summary, int daysBack) {
        jdbcTemplate.update(
                "INSERT INTO tp_ai_task_event (job_kind, job_ref, run_token, level, summary, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, now() - make_interval(days => ?))",
                kind, ref, token, level, summary, daysBack);
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_ai_task_event WHERE job_kind = ? AND job_ref = ?"
                        + " AND summary = ? ORDER BY id DESC LIMIT 1",
                Long.class, kind, ref, summary);
        assertNotNull(id, "fixture insert must return its id");
        return id;
    }

    private long countUnderKind(String kind, String ref) {
        Long c = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_ai_task_event WHERE job_kind = ? AND job_ref = ?",
                Long.class, kind, ref);
        return c == null ? 0 : c;
    }

    private long countById(long id) {
        Long c = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_ai_task_event WHERE id = ?", Long.class, id);
        return c == null ? 0 : c;
    }

    private long countByRef(String ref) {
        Long c = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_ai_task_event WHERE job_kind = ? AND job_ref = ?",
                Long.class, KIND, ref);
        return c == null ? 0 : c;
    }

    private String readKey(String key) {
        return jdbcTemplate.queryForObject(
                "SELECT config_value FROM tp_sys_config WHERE config_key = ?", String.class, key);
    }

    /** Direct table write plus cache sync — getValue reads the holder, not the row. */
    private void writeKey(String key, String value) {
        jdbcTemplate.update("UPDATE tp_sys_config SET config_value = ? WHERE config_key = ?",
                value, key);
        configCache.put(key, value);
    }

    private void deleteFixtures() {
        jdbcTemplate.update("DELETE FROM tp_ai_task_event WHERE job_kind = ?", KIND);
        // Site-family fixtures carry the l2-site- ref prefix; real site rows key
        // by numeric session ids, so this predicate can never touch live runs.
        jdbcTemplate.update("DELETE FROM tp_ai_task_event WHERE job_kind = ? AND job_ref LIKE 'l2-site-%'", SITE);
    }
}
