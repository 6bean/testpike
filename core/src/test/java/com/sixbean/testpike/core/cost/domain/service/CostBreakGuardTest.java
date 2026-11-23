package com.sixbean.testpike.core.cost.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.ai.api.dto.AiTaskCreateInput;
import com.sixbean.testpike.core.ai.domain.service.AiTaskService;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import com.sixbean.testpike.core.cost.api.dto.BudgetInput;
import com.sixbean.testpike.core.cost.api.dto.CostWaterView;
import com.sixbean.testpike.core.cost.domain.component.CostBreakGuard;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.support.AiCallLogCostFixture;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class CostBreakGuardTest {

    @Autowired
    private CostService costService;

    @Autowired
    private CostBreakGuard guard;

    @Autowired
    private AiTaskService aiTaskService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private ConfigCacheHolder configCacheHolder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String month;

    /** Fixture projects created by this run (tracked for the R37 teardown sweep). */
    private final List<Long> projectIds = new java.util.ArrayList<>();
    /** Run-unique suffix so a crashed earlier run's leftovers can never collide. */
    private static final String TAG = Long.toString(System.nanoTime() % 1_000_000_000L, 36);

    @BeforeEach
    void clean() {
        month = jdbcTemplate.queryForObject("SELECT to_char(now(), 'YYYY-MM')", String.class);
    }

    @AfterEach
    void sweepRunOwnedRows() {
        // R37 shared-db hygiene: delete exactly this run's rows. The project sweep
        // covers tp_notification/tp_ai_call_log/tp_audit_log (project-keyed tables of
        // the shared helper) and cascades tp_cost_budget through the FK graph;
        // tp_ai_task is project-keyed without an FK, so it rides the extra list.
        SharedDbFixtureSweep.byProjectList(jdbcTemplate, List.of("tp_ai_task"), projectIds);
    }

    @AfterEach
    void restoreBreakSwitch() {
        configCacheHolder.put("ai.cost.break-enabled", "true");
    }

    private long createProject(String code) {
        String c = code + "-" + TAG;
        long id = projectService.create(1L, new ProjectCreateInput("项目" + c, c, "zh-CN", "P1", null));
        projectIds.add(id);
        return id;
    }

    private long alertCount(long projectId) {
        Long c = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_notification WHERE event_type = 'COST_BUDGET_ALERT'"
                        + " AND project_id = ?", Long.class, projectId);
        return c == null ? 0 : c;
    }

    @Test
    void waterLevel_monthCentsConversionSinglePoint() {
        long p = createProject("BRK1");
        costService.putBudget(1L, p, new BudgetInput(1000L, 80));
        AiCallLogCostFixture.insert(jdbcTemplate, p, 0.1234);   // yuan -> cents (x100 HALF_UP on DECIMAL(10,4)) = 12

        CostWaterView w = costService.waterLevel(p);

        assertEquals(month, w.month(), "水位月锚=DB 自然月（UTC+8 月切同源）");
        assertEquals(12L, w.costCents(), "元→分换算=movePointRight(2) HALF_UP 单点");
        assertEquals(1000L, w.budgetCents());
        assertEquals(80, w.alertPct());
        assertTrue(w.breakEnabled(), "K8 默认 break-enabled=true");
    }

    @Test
    void belowAlertWater_noAlertNoBreak() {
        long p = createProject("BRK2");
        costService.putBudget(1L, p, new BudgetInput(1000L, 80));
        AiCallLogCostFixture.insert(jdbcTemplate, p, 4.0);   // 40% < 80%

        assertDoesNotThrow(() -> guard.assertGenerationAllowed(p));

        assertEquals(0, alertCount(p), "40%→0% 不误报（TC-E3-01）");
    }

    @Test
    void alertWater_sendsNotificationToOwnerOncePerDay() {
        long p = createProject("BRK3");
        costService.putBudget(1L, p, new BudgetInput(1000L, 80));
        AiCallLogCostFixture.insert(jdbcTemplate, p, 8.0);   // 80% >= alert level, below 100%

        assertDoesNotThrow(() -> guard.assertGenerationAllowed(p), "预警不拒启");
        guard.assertGenerationAllowed(p);
        guard.assertGenerationAllowed(p);

        assertEquals(1, alertCount(p), "COST_BUDGET_ALERT 日去重（TC-E3-01）");
        var info = jdbcTemplate.queryForMap(
                "SELECT user_id, title, content, dedup_key FROM tp_notification"
                        + " WHERE event_type = 'COST_BUDGET_ALERT' AND project_id = ?", p);
        assertEquals(1L, info.get("user_id"), "收件人=项目负责人（创建人自动 role=2）");
        String content = (String) info.get("content");
        assertTrue(content.contains("80%") && content.contains("¥8.00") && content.contains("¥10.00"),
                "人话含水位+已用/预算金额: " + content);
        assertTrue(((String) info.get("dedup_key")).startsWith("COST_BUDGET_ALERT:" + p + ":"),
                "去重键含项目+自然日");
    }

    @Test
    void exhausted_rejects40002HumanReadable() {
        long p = createProject("BRK4");
        costService.putBudget(1L, p, new BudgetInput(500L, 80));
        AiCallLogCostFixture.insert(jdbcTemplate, p, 6.0);   // 120% ≥ 100%

        ServiceException ex = assertThrows(ServiceException.class,
                () -> guard.assertGenerationAllowed(p));
        assertEquals(40002, ex.getCode(), "COST_BUDGET_EXCEEDED");
        assertTrue(ex.getMessage().contains("本月 Token 预算已用尽（¥6.00/¥5.00）"),
                "人话含 ¥X/¥Y: " + ex.getMessage());
    }

    @Test
    void zeroBudgetUnlimited_neverAlertOrBreak() {
        long p = createProject("BRK5");
        costService.putBudget(1L, p, new BudgetInput(0L, 80));
        AiCallLogCostFixture.insert(jdbcTemplate, p, 99999.0);

        assertDoesNotThrow(() -> guard.assertGenerationAllowed(p));
        assertEquals(0, alertCount(p), "不限额无水位可言");
    }

    @Test
    void breakDisabled_hotUpdatePassesThrough() {
        long p = createProject("BRK6");
        costService.putBudget(1L, p, new BudgetInput(100L, 80));
        AiCallLogCostFixture.insert(jdbcTemplate, p, 10.0);   // 1000%
        configCacheHolder.put("ai.cost.break-enabled", "false");

        assertDoesNotThrow(() -> guard.assertGenerationAllowed(p), "开关关=停熔（K8 热更现读）");
        assertTrue(alertCount(p) >= 1, "熔断停但预警仍按水位发（两旗标独立）");
    }

    @Test
    void overrideActive_passesEvenIfExhausted() {
        long p = createProject("BRK7");
        costService.putBudget(1L, p, new BudgetInput(100L, 80));
        AiCallLogCostFixture.insert(jdbcTemplate, p, 10.0);
        jdbcTemplate.update("UPDATE tp_cost_budget SET break_override_until = ? WHERE project_id = ?",
                Timestamp.valueOf(LocalDateTime.now().plusHours(2)), p);

        assertDoesNotThrow(() -> guard.assertGenerationAllowed(p), "临时恢复窗内放行（TC-E3-03 前半）");
    }

    @Test
    void overrideExpired_rebreaksAutomaticallyAtRead() {
        long p = createProject("BRK8");
        costService.putBudget(1L, p, new BudgetInput(100L, 80));
        AiCallLogCostFixture.insert(jdbcTemplate, p, 10.0);
        jdbcTemplate.update("UPDATE tp_cost_budget SET break_override_until = ? WHERE project_id = ?",
                Timestamp.valueOf(LocalDateTime.now().minusMinutes(1)), p);

        assertThrows(ServiceException.class, () -> guard.assertGenerationAllowed(p));
    }

    @Test
    void taskCreate_blockedRunningTaskUninterrupted() {
        long p = createProject("BRK9");
        long taskId = aiTaskService.create(new AiTaskCreateInput(p, 1L, 1, "登录功能生成", null));
        costService.putBudget(1L, p, new BudgetInput(100L, 80));
        AiCallLogCostFixture.insert(jdbcTemplate, p, 1.0);   // budget exhausted (cost = budget)

        ServiceException ex = assertThrows(ServiceException.class,
                () -> aiTaskService.create(new AiTaskCreateInput(p, 1L, 1, "再来一条", null)));
        assertEquals(40002, ex.getCode());
        assertEquals(1L, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_ai_task WHERE project_id = ?", Long.class, p),
                "拒启不得残留任务行（D2 无旁路）");

        aiTaskService.markRunning(taskId);
        aiTaskService.markDone(taskId, 9L);
        assertEquals(3, aiTaskService.get(taskId).status());
    }

    @Test
    void retryOfFailedTask_blockedWhenExhausted() {
        long p = createProject("BRKA");
        long taskId = aiTaskService.create(new AiTaskCreateInput(p, 1L, 1, "登录", null));
        aiTaskService.markRunning(taskId);
        aiTaskService.markFailed(taskId, "MODEL_TIMEOUT", "超时");
        costService.putBudget(1L, p, new BudgetInput(100L, 80));
        AiCallLogCostFixture.insert(jdbcTemplate, p, 2.0);

        assertThrows(ServiceException.class, () -> aiTaskService.retry(taskId));
        assertEquals(4, aiTaskService.get(taskId).status(), "拒重试=任务保持 failed 不回 pending");
    }
}
