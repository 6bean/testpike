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
 * Governance rows that open the prompt-template reads through the dialog pass-through door
 * (migration V383). Read side: the five catalog keys must be registered with scope_verified=1,
 * reachable=1 and a non-blank plain-words description, because the policy refuses an anchor-less
 * entry without such a row. Write side (the invariant this test guards against widening): no POST
 * row for the same family may be registered — changes keep going through the confirmation card,
 * the change-landing switch and their own per-entry human verdict, never a batch seed.
 *
 * <p>Real dev database, no mocks: this asserts the migration landed, which a unit-level policy
 * fixture cannot cover.
 */
@SpringBootTest(classes = CoreApplication.class)
class ApiAccessPromptTemplateGovernanceSeedTest {

    private static final List<String> READ_KEYS = List.of(
            "GET /internal/prompt-templates/families",
            "GET /internal/prompt-templates/versions",
            "GET /internal/prompt-templates/latest",
            "GET /internal/prompt-templates/latest-by-status",
            "GET /internal/prompt-templates/{id}");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void 读条目五条全部登记核验且在可达态() {
        for (String key : READ_KEYS) {
            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "SELECT scope_verified, reachable, ai_desc FROM tp_ai_api_access_governance"
                            + " WHERE endpoint_key = ?", key);
            assertEquals(1, ((Number) row.get("scope_verified")).intValue(),
                    key + " 缺少归属核验登记（scope_verified=1）");
            assertEquals(1, ((Number) row.get("reachable")).intValue(),
                    key + " 被登记为不可达，对话读不到");
            assertNotNull(row.get("ai_desc"), key + " 缺可读说明（ai_desc）");
            assertFalse(String.valueOf(row.get("ai_desc")).isBlank(),
                    key + " 的说明为空，目录里模型读不到指引");
        }
    }

    @Test
    void 写条目不得被批量登记进变更门() {
        // The 2026-11 full-coverage batch registers every catalog entry - but a documentation
        // row grants nothing: write entries must sit CLOSED (scope_verified=0 and reachable=0),
        // so landing still demands its own named row with scope_verified=1, the write switch
        // and the confirmation card. Effect contract, not row absence.
        Integer opened = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_ai_api_access_governance"
                        + " WHERE endpoint_key LIKE 'POST /internal/prompt-templates%'"
                        + " AND (scope_verified = 1 OR reachable = 1)",
                Integer.class);
        assertEquals(0, opened, "变更条目须逐条人判开启，禁止随读取批种子或覆盖批登记放行");
        Integer closed = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_ai_api_access_governance"
                        + " WHERE endpoint_key IN ('POST /internal/prompt-templates',"
                        + " 'POST /internal/prompt-templates/{id}/promote',"
                        + " 'POST /internal/prompt-templates/{sourceId}/rollback')"
                        + " AND scope_verified = 0 AND reachable = 0",
                Integer.class);
        assertEquals(3, closed, "覆盖账要求三枚写条目全部在册且为关闭态");
    }
}
