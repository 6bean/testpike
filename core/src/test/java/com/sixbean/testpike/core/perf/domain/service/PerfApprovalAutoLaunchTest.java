package com.sixbean.testpike.core.perf.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.config.api.dto.ConfigInput;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import com.sixbean.testpike.core.perf.api.dto.PerfApprovalInput;
import com.sixbean.testpike.core.perf.api.dto.PerfScenarioInput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D8 approval-linked auto-launch (K8 switch perf.approval.auto-launch.enabled, default off).
 * <p>Contract pinned here: an approval ticket may link the perf scenario it was filed for.
 * When the switch is on, the transition that turns the ticket approved fires the linked
 * scenario through the exact same single launch channel as the manual and CI doors
 * (safety gate + duration ceiling + GA-2 lock, zero bypass). The approval always commits
 * first — a launch refused by any gate never rolls the approval back, instead the plain
 * reason lands on the ticket note (no silent failure, no invented run).
 * <p>Switch off (the default) keeps today's behaviour byte-for-byte: approving only
 * unlocks the production window, launching stays a human action, note stays NULL.
 * <p>Real chain, no mocks: dev DB fixtures + service calls (posture follows
 * PerfRunLaunchGateTest).
 */
@SpringBootTest(classes = CoreApplication.class)
@org.springframework.test.context.TestPropertySource(properties = "testpike.scheduling.enabled=false")
class PerfApprovalAutoLaunchTest {

    @Autowired
    private PerfScenarioService perfScenarioService;

    @Autowired
    private PerfApprovalService perfApprovalService;

    @Autowired
    private ConfigService configService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;
    private long devEnvId;
    private long operatorId;

    @BeforeEach
    void seedFixtures() {
        cleanupRows();
        operatorId = jdbcTemplate.queryForObject("SELECT min(id) FROM tp_user", Long.class);
        long uniq = System.nanoTime() % 1_000_000_000L;
        jdbcTemplate.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "审批自动起压测试项目_" + uniq, "PFAAL" + uniq);
        projectId = lastId("tp_project");
        jdbcTemplate.update("INSERT INTO tp_project_env (project_id, name, env_type, base_url) "
                        + "VALUES (?, '开发-自动起压', 1, 'http://perf-target.local/api')", projectId);
        devEnvId = lastId("tp_project_env");
        restoreSeed("perf.production-enabled", "false");
        restoreSeed("perf.approval.auto-launch.enabled", "false");
    }

    @AfterEach
    void cleanup() {
        cleanupRows();
        restoreSeed("perf.production-enabled", "false");
        restoreSeed("perf.approval.auto-launch.enabled", "false");
    }

    private void cleanupRows() {
        // FK order: tp_perf_approval.scenario_id references tp_perf_scenario — tickets
        // must go before scenarios (P6-style dependency, delete children first).
        jdbcTemplate.update("DELETE FROM tp_perf_approval WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PFAAL%')");
        jdbcTemplate.update("DELETE FROM tp_perf_metric WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PFAAL%')");
        jdbcTemplate.update("DELETE FROM tp_perf_run WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PFAAL%')");
        jdbcTemplate.update("DELETE FROM tp_perf_scenario WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PFAAL%')");
        jdbcTemplate.update("DELETE FROM tp_exec_task WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PFAAL%')");
        jdbcTemplate.update("DELETE FROM tp_perf_whitelist WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PFAAL%')");
        jdbcTemplate.update("DELETE FROM tp_project_env WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PFAAL%')");
        jdbcTemplate.update("DELETE FROM tp_project WHERE code LIKE 'PFAAL%'");
    }

    private long lastId(String table) {
        return jdbcTemplate.queryForObject("SELECT max(id) FROM " + table, Long.class);
    }

    /** Config write via ConfigService = cache synchronously invalidated (known lesson:
     *  raw SQL update leaves a dirty ConfigCacheHolder entry poisoning later cases). */
    private void restoreSeed(String key, String value) {
        Long id = jdbcTemplate.query("SELECT id FROM tp_sys_config WHERE config_key = ?",
                rs -> rs.next() ? rs.getLong(1) : null, key);
        if (id != null) {
            configService.delete(null, id);
        }
        configService.create(null, new ConfigInput(key, value, "系统", "string", 0,
                "approval auto-launch seed managed by test"));
    }

    private void addWhitelist(long envId, int maxConcurrency) {
        jdbcTemplate.update("INSERT INTO tp_perf_whitelist (project_id, env_id, max_concurrency, "
                        + "allowed_modes) VALUES (?, ?, ?, CAST(? AS jsonb))",
                projectId, envId, maxConcurrency, "[\"SHADOW_TABLE\"]");
    }

    private long newScenario(long envId, String name, int targetRps) {
        return perfScenarioService.create(projectId, new PerfScenarioInput(
                name, envId, "CONSTANT_RPS",
                Map.of("targetRps", targetRps, "durationSeconds", 60), null, null,
                List.of(Map.of("action", "http.request", "method", "GET",
                        "url", "http://perf-target.local/api/orders")),
                null));
    }

    private int countRuns() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_perf_run WHERE project_id = ?", Integer.class, projectId);
    }

    private String noteOf(long approvalId) {
        return jdbcTemplate.queryForObject(
                "SELECT auto_launch_note FROM tp_perf_approval WHERE id = ?",
                String.class, approvalId);
    }

    /** Switch on + linked scenario: approving fires the launch through the single channel —
     *  one run row exists, note names the run id in plain words. */
    @Test
    void approve_开关开关联场景_自动起压并注记运行号() {
        restoreSeed("perf.approval.auto-launch.enabled", "true");
        addWhitelist(devEnvId, 100);
        long scenarioId = newScenario(devEnvId, "审批关联场景", 40);
        long approvalId = perfApprovalService.apply(projectId, operatorId,
                new PerfApprovalInput(devEnvId, 100, "SHADOW_TABLE", "容量验收窗口", scenarioId));

        perfApprovalService.approve(approvalId, operatorId);

        Integer status = jdbcTemplate.queryForObject(
                "SELECT status FROM tp_perf_approval WHERE id = ?", Integer.class, approvalId);
        assertEquals(3, status, "批准必须照常成交");
        assertEquals(1, countRuns(), "开关开启时批准应自动发起恰好一条运行");
        String note = noteOf(approvalId);
        assertNotNull(note, "自动起压成功必须落注记（禁静默）");
        assertTrue(note.contains("#"), "注记应点名运行号: " + note);
        Long linked = perfApprovalService.list(projectId).stream()
                .filter(r -> ((Number) r.get("id")).longValue() == approvalId)
                .map(r -> ((Number) r.get("scenario_id")).longValue())
                .findFirst().orElse(null);
        assertEquals(scenarioId, linked, "审批单列表必须回显关联场景");
    }

    /** Switch off (the default): approval takes effect, zero launch, note stays NULL. */
    @Test
    void approve_开关关_批准生效零发起注记为空() {
        addWhitelist(devEnvId, 100);
        long scenarioId = newScenario(devEnvId, "开关关场景", 40);
        long approvalId = perfApprovalService.apply(projectId, operatorId,
                new PerfApprovalInput(devEnvId, 100, "SHADOW_TABLE", "容量验收窗口", scenarioId));

        perfApprovalService.approve(approvalId, operatorId);

        assertEquals(0, countRuns(), "默认关=批准只解锁窗口，起压仍由人工");
        assertNull(noteOf(approvalId), "未自动起压时注记必须为空");
    }

    /** Launch refused by the gate: approval stays effective, the plain reason lands on the
     *  ticket, no orphan run (a broken auto-launch must never eat a granted approval). */
    @Test
    void approve_开关开但发起被闸拒_审批照常生效注记点名原因() {
        restoreSeed("perf.approval.auto-launch.enabled", "true");
        addWhitelist(devEnvId, 20);
        // scenario above the filed whitelist cap — the launch gate refuses it by name
        long scenarioId = newScenario(devEnvId, "超备案上限场景", 500);
        long approvalId = perfApprovalService.apply(projectId, operatorId,
                new PerfApprovalInput(devEnvId, 500, "SHADOW_TABLE", "容量验收窗口", scenarioId));

        perfApprovalService.approve(approvalId, operatorId);

        Integer status = jdbcTemplate.queryForObject(
                "SELECT status FROM tp_perf_approval WHERE id = ?", Integer.class, approvalId);
        assertEquals(3, status, "自动起压被拒不得回滚已成交的批准");
        assertEquals(0, countRuns(), "闸拒必须整单回滚发起，零运行残留");
        String note = noteOf(approvalId);
        assertNotNull(note, "被拒也必须落注记（禁静默失败）");
        assertTrue(note.contains("未自动发起"), "注记必须人话点名未发起: " + note);
    }

    /** Re-approving an already approved ticket is the idempotent short-circuit — it must
     *  not fire a second launch. */
    @Test
    void approve_重复批准幂等_不二次起压() {
        restoreSeed("perf.approval.auto-launch.enabled", "true");
        addWhitelist(devEnvId, 100);
        long scenarioId = newScenario(devEnvId, "重复批准场景", 40);
        long approvalId = perfApprovalService.apply(projectId, operatorId,
                new PerfApprovalInput(devEnvId, 100, "SHADOW_TABLE", "容量验收窗口", scenarioId));

        perfApprovalService.approve(approvalId, operatorId);
        perfApprovalService.approve(approvalId, operatorId);

        assertEquals(1, countRuns(), "幂等短路不得重复起压");
    }

    /** A scenario link that does not belong to the filing project is refused at apply time
     *  with the non-leaking NOT_FOUND wording (link validation lives at the write point,
     *  not at approve time — an un-checkable ticket must never reach the approve door). */
    @Test
    void apply_关联场景越权或不存在_人话拒单零泄漏() {
        // env leg passes (dev env of this project), scenario leg must refuse: a missing
        // scenario and a foreign scenario share one wording (existence never leaks)
        ServiceException e = assertThrows(ServiceException.class,
                () -> perfApprovalService.apply(projectId, operatorId,
                        new PerfApprovalInput(devEnvId, 100, "SHADOW_TABLE", "跨项目关联", 999_999_999L)));
        assertTrue(e.getMessage().contains("场景"),
                "必须点名场景归属: " + e.getMessage());
        assertEquals(0, (int) countRuns(), "拒单零副作用");
    }
}
