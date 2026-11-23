package com.sixbean.testpike.core.mcp.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.config.api.dto.ConfigInput;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import com.sixbean.testpike.core.mcp.api.dto.McpKnowledgeSyncSaveRequest;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Config-key guard segment for the MCP knowledge sync group .
 *
 * <p>Contract asserted here against the real local DB, no mocks: a subscription
 * saved without an explicit cadence takes the hot-updated
 * {@code ai.mcp.sync.interval-minutes} value; the {@code ai.mcp.sync.enabled}
 * master switch short-circuits a whole scan round to an empty ledger;
 * {@code ai.mcp.sync.max-entries-per-round} truncates the due list one round
 * executes; the {@code ai.mcp.sync.failure-threshold} edge pauses the
 * subscription exactly once with an admin notification and a success clears
 * the consecutive-failure counter back to zero; and the write guard rejects
 * out-of-range or non-bool values for the group while accepting in-range ones.
 *
 * <p>Data hygiene (R37): every subscription/project fixture is deleted by its
 * own id in {@link #cleanOwnRows}; factory-seeded tp_sys_config rows are only
 * touched through {@link #setConfigValue} and restored to their seeded value
 * in each test's finally path — they are never truncated or deleted.
 */
@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = {"testpike.scheduling.enabled=false"})
class McpKnowledgeSyncConfigGuardTest {

    private static final String KEY_ENABLED = "ai.mcp.sync.enabled";
    private static final String KEY_INTERVAL = "ai.mcp.sync.interval-minutes";
    private static final String KEY_MAX_ENTRIES = "ai.mcp.sync.max-entries-per-round";
    private static final String KEY_THRESHOLD = "ai.mcp.sync.failure-threshold";

    @Autowired
    private McpKnowledgeSyncService syncService;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private ConfigService configService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;
    private long serverId;
    private String suffix;
    private final List<Long> createdSubscriptionIds = new ArrayList<>();

    @BeforeEach
    void createFixtures() {
        suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        projectId = projectService.create(1L, new ProjectCreateInput(
                "MCP guard project " + suffix, "guard-" + suffix, "zh-CN", "P1", null));
        String snapshot = """
                [
                  {"name":"readOnlyDocs","description":"fetch docs","inputSchemaDigest":"d1","enabled":true,
                   "annotations":{"readOnlyHint":true,"destructiveHint":false}},
                  {"name":"writeDocs","description":"mutate docs","inputSchemaDigest":"d2","enabled":true,
                   "annotations":{"readOnlyHint":false}}
                ]""";
        jdbcTemplate.update("INSERT INTO tp_mcp_server (name, endpoint, transport, tools_json, enabled)"
                        + " VALUES (?, ?, 2, ?::jsonb, 1)",
                "guard-server-" + suffix, "http://127.0.0.1:1/mcp", snapshot);
        serverId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_mcp_server WHERE name = ?", Long.class, "guard-server-" + suffix);
    }

    @AfterEach
    void cleanOwnRows() {
        McpSyncSubscriptionCleaner.purge(jdbcTemplate, createdSubscriptionIds);
        createdSubscriptionIds.clear();
        jdbcTemplate.update("DELETE FROM tp_mcp_server WHERE id = ?", serverId);
    }

    @Test
    void nullIntervalTakesConfiguredDefault() {
        String seeded = configValue(KEY_INTERVAL);
        long id = saveSubscription("null-interval-" + suffix, null);
        Integer stored = jdbcTemplate.queryForObject(
                "SELECT sync_interval_minutes FROM tp_mcp_knowledge_sync WHERE id = ?", Integer.class, id);
        assertEquals(Integer.parseInt(seeded), stored,
                "a subscription saved without a cadence must take the hot-updated key default");
    }

    @Test
    void masterSwitchOffScansNothing() {
        try {
            long id = saveSubscription("switch-" + suffix, 1440);
            setConfigValue(KEY_ENABLED, "false");
            assertEquals(0, syncService.scanDue().scanned(),
                    "an off ai.mcp.sync.enabled master switch must short-circuit the whole round");
            Boolean touched = jdbcTemplate.queryForObject(
                    "SELECT last_sync_at IS NOT NULL FROM tp_mcp_knowledge_sync WHERE id = ?",
                    Boolean.class, id);
            assertFalse(touched, "the skipped subscription must not have been executed or ledgered");
        } finally {
            setConfigValue(KEY_ENABLED, "true");
        }
        // On-path sanity: the same due row must become visible again once the switch is true.
        assertTrue(syncService.scanDue().scanned() >= 1,
                "turning the switch back on must let the due subscription run again");
    }

    @Test
    void perRoundCapTruncatesDueList() {
        try {
            saveSubscription("cap-a-" + suffix, 1440);
            saveSubscription("cap-b-" + suffix, 1440);
            saveSubscription("cap-c-" + suffix, 1440);
            setConfigValue(KEY_MAX_ENTRIES, "2");
            assertEquals(2, syncService.scanDue().scanned(),
                    "one round must execute at most ai.mcp.sync.max-entries-per-round due subscriptions");
        } finally {
            setConfigValue(KEY_MAX_ENTRIES, "200");
        }
    }

    @Test
    void failureThresholdEdgePausesAndAlerts() {
        jdbcTemplate.update("DELETE FROM tp_notification WHERE title = ?",
                "定时任务执行失败：mcpKnowledgeSyncScan");
        long id = saveSubscription("edge-" + suffix, 1440);
        String name = "edge-" + suffix;
        syncService.runOnce(1L, id);
        syncService.runOnce(1L, id);
        assertEquals(2, consecutiveFailures(id), "two failed rounds must count two consecutive failures");
        assertNotEquals(Boolean.FALSE, enabledFlag(id), "below the threshold the subscription keeps running");
        assertEquals(0, notificationCountFor(name), "no alert may fire below the threshold");

        syncService.runOnce(1L, id);
        assertEquals(Boolean.FALSE, enabledFlag(id),
                "reaching the threshold edge must auto-pause the subscription");
        assertTrue(String.valueOf(lastError(id)).contains("已自动暂停"),
                "the ledger error must tell the operator the row was auto-paused and can be resumed");
        assertTrue(notificationCountFor(name) >= 1,
                "the threshold edge must send the admin a JOB_FAILED alert naming the subscription");
        // Edge semantics: one more failure past the threshold must not re-alert.
        syncService.resume(1L, id);
        syncService.runOnce(1L, id);
        assertEquals(1, consecutiveFailures(id), "resume must restart the counter path from zero");

        // Success clears the counter: a landed payload resets consecutive_failures to 0.
        syncService.ingestPayload(id, "guard payload " + suffix, "https://mcp.invalid/docs/" + suffix);
        assertEquals(0, consecutiveFailures(id),
                "a successful round must reset consecutive_failures to zero");
    }

    @Test
    void writeGuardRejectsOutOfRangeAndNonBool() {
        try {
            assertThrows(RuntimeException.class, () -> setConfigValue(KEY_INTERVAL, "43201"),
                    "interval above the range must be rejected by the write guard");
            assertThrows(RuntimeException.class, () -> setConfigValue(KEY_INTERVAL, "abc"),
                    "non-numeric interval must be rejected by the write guard");
            assertThrows(RuntimeException.class, () -> setConfigValue(KEY_THRESHOLD, "0"),
                    "threshold below the range must be rejected by the write guard");
            assertThrows(RuntimeException.class, () -> setConfigValue(KEY_ENABLED, "maybe"),
                    "non-bool value for the switch must be rejected by the write guard");
            assertDoesNotThrow(() -> setConfigValue(KEY_INTERVAL, "1000"),
                    "an in-range interval must be accepted");
            assertDoesNotThrow(() -> setConfigValue(KEY_MAX_ENTRIES, "5000"),
                    "the range boundary itself must be accepted");
        } finally {
            setConfigValue(KEY_INTERVAL, "1440");
            setConfigValue(KEY_MAX_ENTRIES, "200");
            setConfigValue(KEY_THRESHOLD, "3");
            setConfigValue(KEY_ENABLED, "true");
        }
    }

    private long saveSubscription(String name, Integer intervalMinutes) {
        McpKnowledgeSyncSaveRequest request = new McpKnowledgeSyncSaveRequest(
                projectId, serverId, name, "readOnlyDocs", Map.of(), intervalMinutes, 0L, 0L, null);
        long id = syncService.save(1L, request).id();
        createdSubscriptionIds.add(id);
        return id;
    }

    private String configValue(String key) {
        return jdbcTemplate.queryForObject(
                "SELECT config_value FROM tp_sys_config WHERE config_key = ?", String.class, key);
    }

    /** Hot-updates one seeded config row through the real write path (cache sync included). */
    private void setConfigValue(String key, String value) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT id, config_key, group_name, value_type, encrypt_flag, description"
                        + " FROM tp_sys_config WHERE config_key = ?", key);
        configService.update(1L, ((Number) row.get("id")).longValue(), new ConfigInput(
                key, value, (String) row.get("group_name"), (String) row.get("value_type"),
                ((Number) row.get("encrypt_flag")).intValue(), (String) row.get("description")));
    }

    private int consecutiveFailures(long id) {
        Integer value = jdbcTemplate.queryForObject(
                "SELECT consecutive_failures FROM tp_mcp_knowledge_sync WHERE id = ?", Integer.class, id);
        return value == null ? 0 : value;
    }

    private Boolean enabledFlag(long id) {
        return jdbcTemplate.queryForObject(
                "SELECT enabled FROM tp_mcp_knowledge_sync WHERE id = ?", Boolean.class, id);
    }

    private String lastError(long id) {
        return jdbcTemplate.queryForObject(
                "SELECT last_error FROM tp_mcp_knowledge_sync WHERE id = ?", String.class, id);
    }

    private int notificationCountFor(String subscriptionName) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_notification WHERE event_type = 'JOB_FAILED'"
                        + " AND content LIKE ?", Integer.class, "%" + subscriptionName + "%");
        return count == null ? 0 : count;
    }
}
