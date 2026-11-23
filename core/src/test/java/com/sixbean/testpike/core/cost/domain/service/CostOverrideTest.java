package com.sixbean.testpike.core.cost.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cost.api.dto.BreakOverrideInput;
import com.sixbean.testpike.core.cost.api.dto.BreakOverrideView;
import com.sixbean.testpike.core.cost.api.dto.BudgetInput;
import com.sixbean.testpike.core.cost.domain.component.CostBreakGuard;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.support.AiCallLogCostFixture;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class CostOverrideTest {

    @Autowired
    private CostService costService;

    @Autowired
    private CostBreakGuard guard;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Fixture projects created by this run (tracked for the R37 teardown sweep). */
    private final List<Long> projectIds = new java.util.ArrayList<>();
    /** Run-unique suffix so a crashed earlier run's leftovers can never collide. */
    private static final String TAG = Long.toString(System.nanoTime() % 1_000_000_000L, 36);

    @AfterEach
    void sweepRunOwnedRows() {
        // tp_audit_log is append-only (a trigger blocks DELETE); audit assertions
        // anchor on object_id = the freshly created project id instead of wiping it.
        SharedDbFixtureSweep.byProjectList(jdbcTemplate, List.of("tp_ai_task"), projectIds);
    }

    private long createProject(String base) {
        String c = base + "-" + TAG;
        long id = projectService.create(1L, new ProjectCreateInput("项目" + c, c, "zh-CN", "P1", null));
        projectIds.add(id);
        return id;
    }

    private void exhaust(long projectId) {
        costService.putBudget(1L, projectId, new BudgetInput(100L, 80));
        AiCallLogCostFixture.insert(jdbcTemplate, projectId, 10.0);   // 1000% of budget -> guard must break
        assertThrows(ServiceException.class,
                () -> guard.assertGenerationAllowed(projectId), "前置：未 override 必熔断");
    }

    @Test
    void breakOverride_blankReason_rejected() {
        long p = createProject("OVR1");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> costService.breakOverride(1L, p, new BreakOverrideInput("  ", 24)));
        assertEquals(40001, ex.getCode());
        assertTrue(ex.getMessage().contains("理由"), "人话点名理由字段: " + ex.getMessage());

        assertThrows(ServiceException.class,
                () -> costService.breakOverride(1L, p, new BreakOverrideInput(null, 24)));
        assertEquals(0, countBudgetRows(p), "拒写不得残留覆写行");
    }

    @Test
    void breakOverride_reasonOver500_rejected() {
        long p = createProject("OVR2");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> costService.breakOverride(1L, p, new BreakOverrideInput("并".repeat(501), 24)));
        assertEquals(40001, ex.getCode());
        assertTrue(ex.getMessage().contains("500"), "人话含上限 500: " + ex.getMessage());
    }

    @Test
    void breakOverride_hoursOutOfRange_rejected() {
        long p = createProject("OVR3");
        for (Integer hours : new Integer[]{0, 73, -2}) {
            ServiceException ex = assertThrows(ServiceException.class,
                    () -> costService.breakOverride(1L, p, new BreakOverrideInput("故障恢复", hours)),
                    "时限 " + hours + " 必拒（IT-E3-02：0/73h/负）");
            assertEquals(40001, ex.getCode());
            assertTrue(ex.getMessage().contains("72"), "人话含合法域 1~72: " + ex.getMessage());
        }
        assertEquals(0, countBudgetRows(p), "值域拒写不得残留覆写行");
    }

    @Test
    void breakOverride_legal_writesColumnsAndAuditAndPassesGuard() {
        long p = createProject("OVR4");
        exhaust(p);

        BreakOverrideView view = costService.breakOverride(1L, p, new BreakOverrideInput("大促保障临时放行", 48));

        assertNotNull(view.overrideUntil(), "返回视图含到期时间");
        assertEquals("大促保障临时放行", view.reason());
        var row = jdbcTemplate.queryForMap(
                "SELECT break_override_reason, break_override_until > now() AS future,"
                        + " break_override_until >= now() + interval '47 hours' AS at_least_48h"
                        + " FROM tp_cost_budget WHERE project_id = ?", p);
        assertEquals("大促保障临时放行", row.get("break_override_reason"));
        assertEquals(Boolean.TRUE, row.get("future"), "到期时间必在未来（DB now()+hours 同源时钟）");
        assertEquals(Boolean.TRUE, row.get("at_least_48h"), "到期覆盖 48h 窗口");

        assertDoesNotThrow(() -> guard.assertGenerationAllowed(p), "恢复期生成放行（TC-E3-03）");

        Long audits = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_audit_log WHERE object_type = 'cost-budget'"
                        + " AND action = 'break-override' AND object_id = ?", Long.class, String.valueOf(p));
        assertEquals(1L, audits, "override 操作进审计（TC-E3-03 操作两条之一）");
        Long operator = jdbcTemplate.queryForObject(
                "SELECT operator_id FROM tp_audit_log WHERE object_type = 'cost-budget'"
                        + " AND action = 'break-override' AND object_id = ?", Long.class, String.valueOf(p));
        assertEquals(1L, operator, "审计操作人=入参 operatorId（无请求上下文回退首参，T101 基径）");
    }

    @Test
    void breakOverride_noExistingRow_materializesEffectiveBudget() {
        long p = createProject("OVR5");
        long defaultCents = costService.getBudget(p).budgetCents();

        costService.breakOverride(1L, p, new BreakOverrideInput("全局默认下应急放行", 24));

        var row = jdbcTemplate.queryForMap(
                "SELECT budget_cents, alert_pct FROM tp_cost_budget WHERE project_id = ?", p);
        assertEquals(defaultCents, ((Number) row.get("budget_cents")).longValue(),
                "物化后生效预算=继承态同值（override 不得改变预算语义）");
        assertEquals(80, ((Number) row.get("alert_pct")).intValue());
    }

    @Test
    void breakOverride_projectMissing_rejected404() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> costService.breakOverride(1L, 999999L, new BreakOverrideInput("恢复", 24)));
        assertEquals(40401, ex.getCode());
    }

    @Test
    void expireOverrides_clearsOnlyExpiredAndRebreaks() {
        long pExpired = createProject("OVR6");
        long pActive = createProject("OVR7");
        exhaust(pExpired);
        costService.breakOverride(1L, pExpired, new BreakOverrideInput("已到期示例", 24));
        costService.breakOverride(1L, pActive, new BreakOverrideInput("仍在窗内", 24));
        jdbcTemplate.update("UPDATE tp_cost_budget SET break_override_until = ? WHERE project_id = ?",
                Timestamp.valueOf(LocalDateTime.now().minusMinutes(5)), pExpired);

        int cleared = costService.clearExpiredOverrides();

        // Shared dev db: the job may also collect expired rows of other fixtures, so
        // the global count is a lower bound here. "clears only expired" is pinned by
        // the per-project column assertions below (expired cleared, active kept).
        assertTrue(cleared >= 1, "至少清掉本运行的过期覆写行（IT-E3-02 到期任务登记）");
        var expired = jdbcTemplate.queryForMap(
                "SELECT break_override_until, break_override_reason FROM tp_cost_budget WHERE project_id = ?",
                pExpired);
        assertNull(expired.get("break_override_until"), "到期列已清（Job 兜底留痕；审计保留原操作）");
        assertNull(expired.get("break_override_reason"));
        assertNotNull(jdbcTemplate.queryForObject(
                "SELECT break_override_until FROM tp_cost_budget WHERE project_id = ?",
                Timestamp.class, pActive), "未到期行不误清");
        assertDoesNotThrow(() -> guard.assertGenerationAllowed(pActive), "窗内仍放行");
        assertThrows(ServiceException.class, () -> guard.assertGenerationAllowed(pExpired),
                "到期未续=自动重熔再拒启（TC-E3-03）");
    }

    private int countBudgetRows(long projectId) {
        Long c = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_cost_budget WHERE project_id = ?", Long.class, projectId);
        return c == null ? 0 : c.intValue();
    }
}
