package com.sixbean.testpike.core.apiaccess.domain.component;

import com.sixbean.testpike.core.CoreApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Governance rows of the dead-letter family against the live database: the two count reads
 * (migration V402, backfilled to the page permission by V404) plus the permission-gated
 * browse read and void change (V404).
 *
 * <p>Invariants, in the words of the per-entry verdicts:
 * <ul>
 *   <li>every dead-letter READ registered here is gated by the page's own read permission — the
 *       dialog may serve exactly the face perm:system:dead-letter-read already shows, never wider
 *       (before the gate existed the counts were the only half aligned; V404 backfills them);</li>
 *   <li>the browse read — ledger rows with message payloads, the reported blocker for per-row
 *       void drafts — is registered WITH that permission, so a holder reads the page face from
 *       the dialog and a non-holder hears the permission point and where it is granted;</li>
 *   <li>the void POST entry is registered with its page ACTION permission; landing keeps every
 *       existing guard (confirmation card, shipped-off write switch, per-entry row);</li>
 *   <li>the replay POST entries are registered only as CLOSED documentation rows (sv=0,
 *       reachable=0): batch re-execution against live exchanges awaits its own named
 *       verdict - a coverage row must never double as a landing permit.</li>
 * </ul>
 */
@SpringBootTest(classes = CoreApplication.class)
class ApiAccessDeadLetterGovernanceSeedTest {

    private static final List<String> COUNT_KEYS = List.of(
            "GET /internal/mq-dead-letter/queue-backlog",
            "GET /internal/mq-dead-letter/class-distribution");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void countReadsAreRegisteredReachableAndGatedToThePagePermission() {
        for (String key : COUNT_KEYS) {
            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "SELECT scope_verified, reachable, ai_desc, required_permission"
                            + " FROM tp_ai_api_access_governance WHERE endpoint_key = ?", key);
            assertEquals(1, ((Number) row.get("scope_verified")).intValue(),
                    key + " 缺少归属核验登记（scope_verified=1）");
            assertEquals(1, ((Number) row.get("reachable")).intValue(),
                    key + " 被登记为不可达，对话读不到");
            assertNotNull(row.get("ai_desc"), key + " 缺可读说明（ai_desc）");
            assertFalse(String.valueOf(row.get("ai_desc")).isBlank(),
                    key + " 的说明为空，目录里模型读不到指引");
            assertEquals("perm:system:dead-letter-read", String.valueOf(row.get("required_permission")),
                    key + " 必须与页面的权限面同宽：页面数计面钉着该权限点，门不得比页面更宽");
        }
    }

    @Test
    void payloadReadAndVoidChangeAreRegisteredWithTheirPagePermissions() {
        Map<String, Object> browse = jdbcTemplate.queryForMap(
                "SELECT scope_verified, reachable, required_permission"
                        + " FROM tp_ai_api_access_governance"
                        + " WHERE endpoint_key = 'GET /internal/mq-dead-letter/browse'");
        assertEquals(1, ((Number) browse.get("scope_verified")).intValue());
        assertEquals(1, ((Number) browse.get("reachable")).intValue());
        assertEquals("perm:system:dead-letter-read", String.valueOf(browse.get("required_permission")),
                "含正文的浏览条目只有钉住页面权限点才允许对对话开放");

        Map<String, Object> voidEntry = jdbcTemplate.queryForMap(
                "SELECT scope_verified, reachable, required_permission"
                        + " FROM tp_ai_api_access_governance"
                        + " WHERE endpoint_key = 'POST /internal/mq-dead-letter/void'");
        assertEquals(1, ((Number) voidEntry.get("scope_verified")).intValue());
        assertEquals("perm:system:dead-letter-void", String.valueOf(voidEntry.get("required_permission")),
                "变更条目必须与其页面按钮同一权限点");
    }

    @Test
    void replayChangeEntriesStayClosed() {
        // The full-coverage ledger registers the replay POSTs as documentation rows, but they
        // must sit CLOSED: batch re-execution against live exchanges awaits its own named
        // verdict, and a registration row must never double as a landing permit.
        Integer opened = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_ai_api_access_governance"
                        + " WHERE endpoint_key LIKE 'POST /internal/mq-dead-letter/replay%'"
                        + " AND (scope_verified = 1 OR reachable = 1)",
                Integer.class);
        assertEquals(0, opened, "批量重放是更强的生产变更面，须逐条点名开启，覆盖账登记不构成放行");
        Integer closed = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_ai_api_access_governance"
                        + " WHERE endpoint_key LIKE 'POST /internal/mq-dead-letter/replay%'"
                        + " AND scope_verified = 0 AND reachable = 0",
                Integer.class);
        assertEquals(3, closed, "三枚重放条目须在册为封闭态");
    }
}
