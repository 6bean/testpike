package com.sixbean.testpike.core.mcp.domain.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.mcp.api.dto.McpKnowledgeSyncLandingResult;
import com.sixbean.testpike.core.mcp.api.dto.McpKnowledgeSyncRoundResult;
import com.sixbean.testpike.core.mcp.api.dto.McpKnowledgeSyncSaveRequest;
import com.sixbean.testpike.core.mcp.api.dto.McpKnowledgeSyncScanResult;
import com.sixbean.testpike.core.mcp.domain.service.impl.McpKnowledgeSyncServiceImpl;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
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
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MCP knowledge sync loop segment .
 *
 * <p>Contract asserted here against the real local DB, no mocks:
 * {@code scanDue} only visits rows whose interval has elapsed (SQL-side due
 * judgement) and short-circuits on an unreachable server through the existing
 * connectivity probe — the failure is typed and written to the subscription
 * ledger (consecutive_failures) instead of aborting the loop;
 * {@code runOnce} keeps counting failures round after round;
 * {@code ingestPayload} lands tool output into the G1 knowledge ledger as
 * source_type MCP_SYNC with status INACTIVE (pending activation, quality
 * gate E2-style), replays of an identical payload are fingerprint-deduped to
 * zero new rows (idempotency), a changed payload lands a new version row, and
 * an explicitly auto-activating subscription lands ACTIVE.
 *
 * <p>Data hygiene (R37): every row created here is deleted by its own id in
 * {@link #cleanOwnRows}; factory-seeded tables are never truncated. The MCP
 * server fixture points at a closed port (127.0.0.1:1) so the DOWN path is
 * deterministic without any network dependency.
 */
@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = {"testpike.scheduling.enabled=false"})
class McpKnowledgeSyncLoopTest {

    @Autowired
    private McpKnowledgeSyncService syncService;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private SchedulerJobCatalog jobCatalog;

    private long projectId;
    private long serverId;
    private final List<Long> createdSubscriptionIds = new ArrayList<>();
    private String suffix;

    @BeforeEach
    void createFixtures() {
        suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        projectId = projectService.create(1L, new ProjectCreateInput(
                "MCP loop project " + suffix, "loop-" + suffix, "zh-CN", "P1", null));
        String snapshot = """
                [
                  {"name":"readOnlyDocs","description":"fetch docs","inputSchemaDigest":"d1","enabled":true,
                   "annotations":{"readOnlyHint":true,"destructiveHint":false}},
                  {"name":"writeDocs","description":"mutate docs","inputSchemaDigest":"d2","enabled":true,
                   "annotations":{"readOnlyHint":false}}
                ]""";
        jdbcTemplate.update("INSERT INTO tp_mcp_server (name, endpoint, transport, tools_json, enabled)"
                        + " VALUES (?, ?, 2, ?::jsonb, 1)",
                "loop-server-" + suffix, "http://127.0.0.1:1/mcp", snapshot);
        serverId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_mcp_server WHERE name = ?", Long.class, "loop-server-" + suffix);
    }

    @AfterEach
    void cleanOwnRows() {
        McpSyncSubscriptionCleaner.purge(jdbcTemplate, createdSubscriptionIds);
        jdbcTemplate.update("DELETE FROM tp_mcp_server WHERE id = ?", serverId);
        // FK child first (precedent: McpKnowledgeSyncServiceTest cleanup ordering).
        jdbcTemplate.update("DELETE FROM tp_user_project WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
    }

    private long saveSubscription(String name, boolean autoActivate) {
        McpKnowledgeSyncSaveRequest request = new McpKnowledgeSyncSaveRequest(
                projectId, serverId, name, "readOnlyDocs",
                Map.of("query", "release notes"), 360, projectId, 0L, autoActivate);
        createdSubscriptionIds.add(syncService.save(1L, request).id());
        return createdSubscriptionIds.get(createdSubscriptionIds.size() - 1);
    }

    private void setLastSyncAt(long subscriptionId, String nowMinus) {
        jdbcTemplate.update("UPDATE tp_mcp_knowledge_sync SET last_sync_at = now() - INTERVAL '"
                + nowMinus + "' WHERE id = ?", subscriptionId);
    }

    // Lineage table is the join key: doc rows are versioned as
    // source_ref mcp-sub-{id}:v{n} because uq_kb_doc_entity(source_type,
    // project_id, source_ref) collides on a plain subscription-id ref.
    private int ledgerRowCount(long subscriptionId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_mcp_knowledge_sync_entry e"
                        + " JOIN tp_knowledge_doc d ON d.id = e.doc_id WHERE e.subscription_id = ?",
                Integer.class, subscriptionId);
        return count == null ? 0 : count;
    }

    private String ledgerStatus(long subscriptionId) {
        return jdbcTemplate.queryForObject(
                "SELECT d.status FROM tp_mcp_knowledge_sync_entry e"
                        + " JOIN tp_knowledge_doc d ON d.id = e.doc_id"
                        + " WHERE e.subscription_id = ? ORDER BY e.version_no DESC LIMIT 1",
                String.class, subscriptionId);
    }

    @Test
    void scanDueVisitsOnlyIntervalElapsedRows() {
        long dueId = saveSubscription("due-" + suffix, false);
        long freshId = saveSubscription("fresh-" + suffix, false);
        setLastSyncAt(dueId, "1 DAY");
        // freshId keeps last_sync_at NULL = never synced = due on first scan;
        // prove exclusion by stamping it now instead.
        jdbcTemplate.update("UPDATE tp_mcp_knowledge_sync SET last_sync_at = now() WHERE id = ?", freshId);

        McpKnowledgeSyncScanResult result = syncService.scanDue();

        assertTrue(result.scanned() >= 1, "due subscription must be scanned");
        // Server is unreachable: the due row failed this round and wrote its ledger.
        assertEquals("failed", subscriptionColumn(dueId, "last_status"));
        assertEquals(1, subscriptionColumnAsInt(dueId, "consecutive_failures"));
        assertTrue(subscriptionColumn(dueId, "last_error") != null,
                "failure must name the reason in the ledger");
        // The freshly synced row was not visited: its ledger stays untouched.
        assertNull(subscriptionColumn(freshId, "last_status"), "not-yet-due row must stay unvisited");
        assertTrue(result.failed() >= 1);
    }

    @Test
    void runOnceOnUnreachableServerFailsTypedAndKeepsCounting() {
        long id = saveSubscription("down-" + suffix, false);

        McpKnowledgeSyncRoundResult first = syncService.runOnce(1L, id);
        assertEquals("failed", first.status());
        assertNotNull(first.error(), "round result must carry the typed failure reason");
        assertEquals(1, subscriptionColumnAsInt(id, "consecutive_failures"));

        // Second consecutive round keeps counting (K2 alert threshold consumes this
        // column later; the loop itself must never throw the schedule dead).
        McpKnowledgeSyncRoundResult second = syncService.runOnce(1L, id);
        assertEquals("failed", second.status());
        assertEquals(2, subscriptionColumnAsInt(id, "consecutive_failures"));
    }

    @Test
    void failingRoundLogsWarnCarryingSubscriptionAndLedgerErrorText() {
        // Observability contract: a failed round must leave a server-side WARN
        // log that reconstructs the ledger error text (last_error alone was
        // proven insufficient when an intermittent transport failure left no
        // trace in the server log during E2E triage).
        LoggerContext context = (LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory();
        ch.qos.logback.classic.Logger target = context.getLogger(McpKnowledgeSyncServiceImpl.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.setContext(context);
        appender.start();
        target.addAppender(appender);
        try {
            long id = saveSubscription("warn-" + suffix, false);
            McpKnowledgeSyncRoundResult round = syncService.runOnce(1L, id);
            assertEquals("failed", round.status());
            String ledgerError = subscriptionColumn(id, "last_error");
            assertNotNull(ledgerError, "ledger must carry the failure reason");

            assertTrue(appender.list.stream().anyMatch(e ->
                            e.getLevel().isGreaterOrEqual(Level.WARN)
                                    && e.getFormattedMessage().contains("subscription=" + id)),
                    "failed round must emit a WARN log naming the subscription");
            assertTrue(appender.list.stream().anyMatch(e ->
                            e.getLevel().isGreaterOrEqual(Level.WARN)
                                    && e.getFormattedMessage().contains(ledgerError)),
                    "the WARN line must carry the same error text written to the ledger");
        } finally {
            target.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void payloadLandsAsPendingActivationInSyncBucket() {
        long id = saveSubscription("land-" + suffix, false);

        McpKnowledgeSyncLandingResult landed = syncService.ingestPayload(
                id, "Release notes for 2026.10: two new endpoints.", "http://external/docs/notes");

        assertEquals(1, landed.inserted());
        assertEquals(0, landed.skipped());
        assertEquals(1, ledgerRowCount(id));
        assertEquals("INACTIVE", ledgerStatus(id), "synced entries await human batch activation by default");
        assertEquals("updated", subscriptionColumn(id, "last_status"));
        assertEquals(1, subscriptionColumnAsInt(id, "last_new_count"));
    }

    @Test
    void identicalPayloadReplayIsFingerprintDeduped() {
        long id = saveSubscription("dedup-" + suffix, false);
        String payload = "Stable document body for dedup assertion.";

        McpKnowledgeSyncLandingResult first = syncService.ingestPayload(
                id, payload, "http://external/docs/stable");
        McpKnowledgeSyncLandingResult replay = syncService.ingestPayload(
                id, payload, "http://external/docs/stable");

        assertEquals(1, first.inserted());
        assertEquals(0, replay.inserted(), "fingerprint dedup: identical payload must not add rows");
        assertEquals(1, replay.skipped());
        assertEquals(1, ledgerRowCount(id), "ledger row count stays 1 across identical replays");
        assertEquals("unchanged", subscriptionColumn(id, "last_status"));
    }

    @Test
    void changedPayloadLandsNewVersionRow() {
        long id = saveSubscription("version-" + suffix, false);

        syncService.ingestPayload(id, "Document body version one.", "http://external/docs/ver");
        McpKnowledgeSyncLandingResult next = syncService.ingestPayload(
                id, "Document body version two (content changed).", "http://external/docs/ver");

        assertEquals(1, next.inserted());
        assertEquals(2, ledgerRowCount(id), "external revision = new version row, old kept for lineage");
    }

    @Test
    void autoActivateSubscriptionLandsActiveImmediately() {
        long id = saveSubscription("active-" + suffix, true);

        syncService.ingestPayload(id, "Content for the explicit auto-activate path.",
                "http://external/docs/fast");

        assertEquals("ACTIVE", ledgerStatus(id),
                "auto_activate is an explicit per-subscription opt-in, audited via the ledger");
    }

    @Test
    void schedulerJobCatalogRegistersTheSyncScanJob() {
        assertTrue(jobCatalog.descriptions().containsKey("mcpKnowledgeSyncScan"),
                "sync scheduler must be registered in the catalog (unlisted jobs fail the delivery gate)");
        assertNotNull(jobCatalog.descriptions().get("mcpKnowledgeSyncScan"));
    }

    private String subscriptionColumn(long id, String column) {
        List<String> values = jdbcTemplate.query(
                "SELECT " + column + " FROM tp_mcp_knowledge_sync WHERE id = ?",
                (rs, n) -> rs.getString(1), id);
        return values.isEmpty() ? null : values.get(0);
    }

    private int subscriptionColumnAsInt(long id, String column) {
        Integer value = jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM tp_mcp_knowledge_sync WHERE id = ?", Integer.class, id);
        return value == null ? -1 : value;
    }
}
