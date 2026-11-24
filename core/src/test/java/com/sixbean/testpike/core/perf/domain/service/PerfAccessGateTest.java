package com.sixbean.testpike.core.perf.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.config.api.dto.ConfigInput;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import com.sixbean.testpike.core.perf.api.dto.PerfApprovalInput;
import com.sixbean.testpike.core.perf.api.dto.PerfGateCheckInput;
import com.sixbean.testpike.core.perf.api.dto.PerfWhitelistInput;
import com.sixbean.testpike.core.perf.domain.component.PerfAccessGate;
import com.sixbean.testpike.core.perf.domain.component.PerfGateDecision;
import com.sixbean.testpike.core.perf.domain.component.PerfGateStep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;


@SpringBootTest(classes = CoreApplication.class)
@org.springframework.test.context.TestPropertySource(properties = "testpike.scheduling.enabled=false")
class PerfAccessGateTest {

    @Autowired
    private PerfAccessGate perfAccessGate;

    @Autowired
    private PerfWhitelistService perfWhitelistService;

    @Autowired
    private PerfApprovalService perfApprovalService;

    @Autowired
    private ConfigService configService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;
    private long prodEnvId;
    private long devEnvId;
    private long operatorId;

    @BeforeEach
    void seedFixtures() {
        operatorId = jdbcTemplate.queryForObject("SELECT min(id) FROM tp_user", Long.class);
        cleanupConfig("perf.production-enabled");
        cleanupConfig("perf.blocked-target-segments");
        cleanupConfig("perf.approval-valid-hours");
        cleanupConfig("perf.budget-margin-minutes");
        cleanupRows();
        long uniq = System.nanoTime() % 1_000_000_000L;
        // Ids are read back through RETURNING so a foreign fixture row with an
        // overridden id can never out-rank max(id) and re-parent these rows.
        projectId = com.sixbean.testpike.core.support.FixtureInserts.insertReturningId(jdbcTemplate,
                "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "压测闸测试项目_" + uniq, "PERFGATE" + uniq);
        prodEnvId = com.sixbean.testpike.core.support.FixtureInserts.insertReturningId(jdbcTemplate,
                "INSERT INTO tp_project_env (project_id, name, env_type, base_url) "
                        + "VALUES (?, '生产-闸测', 4, 'https://erp.prod.internal/api')",
                projectId);
        devEnvId = com.sixbean.testpike.core.support.FixtureInserts.insertReturningId(jdbcTemplate,
                "INSERT INTO tp_project_env (project_id, name, env_type, base_url) "
                        + "VALUES (?, '开发-闸测', 1, 'http://dev.local/api')",
                projectId);
    }

    @AfterEach
    void cleanup() {
        cleanupRows();
        restoreSeed("perf.production-enabled", "false");
        restoreSeed("perf.blocked-target-segments", "");
        restoreSeed("perf.approval-valid-hours", "72");
        restoreSeed("perf.budget-margin-minutes", "10");
    }

    private void cleanupRows() {
        jdbcTemplate.update("DELETE FROM tp_perf_approval WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PERFGATE%')");
        jdbcTemplate.update("DELETE FROM tp_perf_whitelist WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PERFGATE%')");
        jdbcTemplate.update("DELETE FROM tp_project_env WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PERFGATE%')");
        jdbcTemplate.update("DELETE FROM tp_project WHERE code LIKE 'PERFGATE%'");
    }

    private void cleanupConfig(String key) {
        Long id = jdbcTemplate.query("SELECT id FROM tp_sys_config WHERE config_key = ?",
                rs -> rs.next() ? rs.getLong(1) : null, key);
        if (id != null) {
            configService.delete(1L, id);
        }
    }


    @Test
    void blockedSegmentRejectsEvenForDevEnvWithFullGrants() {
        setConfig("perf.blocked-target-segments", ".prod.internal");
        setConfig("perf.production-enabled", "true");
        addWhitelist(devEnvId, 200, List.of("SHADOW_TABLE"));
        approveThrough(projectId, devEnvId, 200, "SHADOW_TABLE");

        PerfGateDecision d = perfAccessGate.evaluate(new PerfGateCheckInput(
                projectId, devEnvId, 100, "SHADOW_TABLE", "svc.prod.internal"));
        assertThat(d.passed()).isFalse();
        assertThat(d.gate()).isEqualTo("BLOCKED_SEGMENT");
        assertThat(d.reason()).contains("禁入段");
    }

    // ---------- CF-8 explainable gate ladder (all doors reported per row; rejection carries
    // what is missing + who can fix + entry) ----------

    @Test
    void rejectedProductionLadderReportsEveryStepWithFixGuidance() {
        // Only the whitelist door is open; global switch and approval are missing.
        // The decision must still return the FULL ladder (not just the first rejection):
        // blocked-segment PASS, global-switch FAIL with who-can-fix + entry, whitelist PASS,
        // isolation/approval/target evaluated as SKIP-or-fail rows.
        addWhitelist(prodEnvId, 500, List.of("SHADOW_TABLE"));

        PerfGateDecision d = perfAccessGate.evaluate(new PerfGateCheckInput(
                projectId, prodEnvId, 100, "SHADOW_TABLE", "https://erp.prod.internal/api"));
        assertThat(d.passed()).isFalse();
        assertThat(d.gate()).isEqualTo("GLOBAL_SWITCH");

        assertThat(d.steps()).isNotEmpty();
        assertThat(d.steps().stream().map(PerfGateStep::code))
                .contains("OWNERSHIP", "BLOCKED_SEGMENT", "GLOBAL_SWITCH", "WHITELIST",
                        "ISOLATION_DECLARATION", "APPROVAL", "ISOLATION_ALLOWED", "TARGET_MATCH");
        assertThat(d.steps()).filteredOn(s -> s.code().equals("OWNERSHIP"))
                .allMatch(s -> PerfGateStep.PASS.equals(s.state()));
        assertThat(d.steps()).filteredOn(s -> s.code().equals("GLOBAL_SWITCH"))
                .allMatch(s -> PerfGateStep.FAIL.equals(s.state())
                        && !s.detail().isBlank() && !s.fixBy().isBlank() && !s.entryPath().isBlank());
        assertThat(d.steps()).filteredOn(s -> s.code().equals("WHITELIST"))
                .allMatch(s -> PerfGateStep.PASS.equals(s.state()));
        // The first FAIL step must match the top-level gate key (UI highlights the blocking door).
        assertThat(d.steps()).filteredOn(PerfGateStep::failed)
                .first().extracting(PerfGateStep::code).isEqualTo("GLOBAL_SWITCH");
    }

    @Test
    void passingProductionLadderReportsAllRelevantStepsPass() {
        setConfig("perf.production-enabled", "true");
        addWhitelist(prodEnvId, 500, List.of("SHADOW_TABLE"));
        approveThrough(projectId, prodEnvId, 500, "SHADOW_TABLE");

        PerfGateDecision d = perfAccessGate.evaluate(new PerfGateCheckInput(
                projectId, prodEnvId, 200, "SHADOW_TABLE", "https://erp.prod.internal/api"));
        assertThat(d.passed()).isTrue();
        assertThat(d.steps()).isNotEmpty();
        // No FAIL rows when the launch is allowed; non-production-only doors show SKIP elsewhere.
        assertThat(d.steps()).noneMatch(PerfGateStep::failed);
    }

    @Test
    void nonProductionLadderSkipsProductionOnlyDoors() {
        addWhitelist(devEnvId, 200, List.of("HEADER_MARK", "SHADOW_TABLE"));

        PerfGateDecision d = perfAccessGate.evaluate(new PerfGateCheckInput(
                projectId, devEnvId, 150, "HEADER_MARK", "http://dev.local/api"));
        assertThat(d.passed()).isTrue();
        assertThat(d.steps()).filteredOn(s -> s.code().equals("GLOBAL_SWITCH"))
                .allMatch(s -> PerfGateStep.SKIP.equals(s.state()));
        assertThat(d.steps()).filteredOn(s -> s.code().equals("APPROVAL"))
                .allMatch(s -> PerfGateStep.SKIP.equals(s.state()));
    }


    @Test
    void productionGateRejectsWhenGlobalSwitchOffByDefault() {
        addWhitelist(prodEnvId, 500, List.of("SHADOW_TABLE"));
        approveThrough(projectId, prodEnvId, 500, "SHADOW_TABLE");

        PerfGateDecision d = perfAccessGate.evaluate(new PerfGateCheckInput(
                projectId, prodEnvId, 100, "SHADOW_TABLE", "https://erp.prod.internal/api"));
        assertThat(d.passed()).isFalse();
        assertThat(d.gate()).isEqualTo("GLOBAL_SWITCH");
        assertThat(d.reason()).contains("全局开关");
    }

    @Test
    void nonProductionEnvPassesWithWhitelistOnly() {
        addWhitelist(devEnvId, 200, List.of("HEADER_MARK", "SHADOW_TABLE"));

        PerfGateDecision d = perfAccessGate.evaluate(new PerfGateCheckInput(
                projectId, devEnvId, 150, "HEADER_MARK", "http://dev.local/api"));
        assertThat(d.passed()).isTrue();
    }


    @Test
    void productionRejectsWithoutProjectWhitelistEntry() {
        setConfig("perf.production-enabled", "true");
        approveThrough(projectId, prodEnvId, 500, "SHADOW_TABLE");

        PerfGateDecision d = perfAccessGate.evaluate(new PerfGateCheckInput(
                projectId, prodEnvId, 100, "SHADOW_TABLE", "https://erp.prod.internal/api"));
        assertThat(d.passed()).isFalse();
        assertThat(d.gate()).isEqualTo("WHITELIST");
        assertThat(d.reason()).contains("白名单");
    }

    @Test
    void concurrencyAboveWhitelistTierRejected() {
        addWhitelist(devEnvId, 100, List.of("SHADOW_TABLE"));

        PerfGateDecision d = perfAccessGate.evaluate(new PerfGateCheckInput(
                projectId, devEnvId, 101, "SHADOW_TABLE", "http://dev.local/api"));
        assertThat(d.passed()).isFalse();
        assertThat(d.gate()).isEqualTo("WHITELIST");
        assertThat(d.reason()).contains("压力档位");
    }

    @Test
    void whitelistDuplicateEnvTierRejected409() {
        perfWhitelistService.create(projectId, new PerfWhitelistInput(devEnvId, 100, List.of("SHADOW_TABLE")));
        try {
            perfWhitelistService.create(projectId, new PerfWhitelistInput(devEnvId, 100, List.of("HEADER_MARK")));
            org.junit.jupiter.api.Assertions.fail("同环境重复档位应 40904");
        } catch (com.sixbean.testpike.common.exception.ServiceException e) {
            assertThat(e.getCode()).isEqualTo(com.sixbean.testpike.common.exception.BizErrorCode.RESOURCE_CONFLICT);
        }
    }


    @Test
    void productionRejectsWithoutApproval() {
        setConfig("perf.production-enabled", "true");
        addWhitelist(prodEnvId, 500, List.of("SHADOW_TABLE"));

        PerfGateDecision d = perfAccessGate.evaluate(new PerfGateCheckInput(
                projectId, prodEnvId, 100, "SHADOW_TABLE", "https://erp.prod.internal/api"));
        assertThat(d.passed()).isFalse();
        assertThat(d.gate()).isEqualTo("APPROVAL");
        assertThat(d.reason()).contains("审批");
    }

    @Test
    void rejectedApprovalDoesNotOpenGate() {
        setConfig("perf.production-enabled", "true");
        addWhitelist(prodEnvId, 500, List.of("SHADOW_TABLE"));
        long id = perfApprovalService.apply(projectId, operatorId,
                new PerfApprovalInput(prodEnvId, 100, "SHADOW_TABLE", "容量验收"));
        perfApprovalService.reject(id, operatorId, "目标系统未备案");

        PerfGateDecision d = perfAccessGate.evaluate(new PerfGateCheckInput(
                projectId, prodEnvId, 100, "SHADOW_TABLE", "https://erp.prod.internal/api"));
        assertThat(d.passed()).isFalse();
        assertThat(d.gate()).isEqualTo("APPROVAL");
    }

    @Test
    void expiredApprovalRejectsWithHumanReason() {
        setConfig("perf.production-enabled", "true");
        setConfig("perf.approval-valid-hours", "24");
        addWhitelist(prodEnvId, 500, List.of("SHADOW_TABLE"));
        long id = approveThroughId(projectId, prodEnvId, 100, "SHADOW_TABLE");
        jdbcTemplate.update("UPDATE tp_perf_approval SET handled_at = now() - interval '25 hours' WHERE id = ?", id);

        PerfGateDecision d = perfAccessGate.evaluate(new PerfGateCheckInput(
                projectId, prodEnvId, 100, "SHADOW_TABLE", "https://erp.prod.internal/api"));
        assertThat(d.passed()).isFalse();
        assertThat(d.gate()).isEqualTo("APPROVAL");
        assertThat(d.reason()).contains("过期");
    }


    @Test
    void isolationModeMissingOnProductionRejected() {
        setConfig("perf.production-enabled", "true");
        addWhitelist(prodEnvId, 500, List.of("SHADOW_TABLE"));
        approveThrough(projectId, prodEnvId, 500, "SHADOW_TABLE");

        PerfGateDecision d = perfAccessGate.evaluate(new PerfGateCheckInput(
                projectId, prodEnvId, 100, null, "https://erp.prod.internal/api"));
        assertThat(d.passed()).isFalse();
        assertThat(d.gate()).isEqualTo("ISOLATION_PRECHECK");
        assertThat(d.reason()).contains("隔离");
    }

    @Test
    void isolationModeNotAllowedByWhitelistRejected() {
        setConfig("perf.production-enabled", "true");
        addWhitelist(prodEnvId, 500, List.of("HEADER_MARK"));
        approveThrough(projectId, prodEnvId, 500, "NS_ACCOUNT");

        PerfGateDecision d = perfAccessGate.evaluate(new PerfGateCheckInput(
                projectId, prodEnvId, 100, "NS_ACCOUNT", "https://erp.prod.internal/api"));
        assertThat(d.passed()).isFalse();
        assertThat(d.gate()).isEqualTo("ISOLATION_PRECHECK");
        assertThat(d.reason()).contains("NS_ACCOUNT");
    }


    @Test
    void productionPassesWithAllThreeLayersOpen() {
        setConfig("perf.production-enabled", "true");
        addWhitelist(prodEnvId, 500, List.of("SHADOW_TABLE"));
        approveThrough(projectId, prodEnvId, 500, "SHADOW_TABLE");

        PerfGateDecision d = perfAccessGate.evaluate(new PerfGateCheckInput(
                projectId, prodEnvId, 200, "SHADOW_TABLE", "https://erp.prod.internal/api"));
        assertThat(d.passed()).isTrue();

        setConfig("perf.production-enabled", "false");
        PerfGateDecision after = perfAccessGate.evaluate(new PerfGateCheckInput(
                projectId, prodEnvId, 200, "SHADOW_TABLE", "https://erp.prod.internal/api"));
        assertThat(after.passed()).isFalse();
        assertThat(after.gate()).isEqualTo("GLOBAL_SWITCH");
    }

    // ---------- U-5 pre-launch summary: server-side budget preview (single source
    // = PerfBudgetPolicy; the wizard must never recompute the hot-updatable margin locally) ----------

    @Test
    void precheckReturnsBudgetPreviewComputedByServerPolicy() throws Exception {
        setConfig("perf.budget-margin-minutes", "15");
        addWhitelist(devEnvId, 200, List.of("SHADOW_TABLE"));
        var pressureConfig = new com.fasterxml.jackson.databind.ObjectMapper()
                .valueToTree(java.util.Map.of("startRps", 10, "peakRps", 100,
                        "rampSeconds", 60, "holdSeconds", 120));

        PerfGateDecision d = perfAccessGate.evaluate(new PerfGateCheckInput(
                projectId, devEnvId, 100, "SHADOW_TABLE", null, "RAMP", pressureConfig));

        assertThat(d.passed()).isTrue();
        // RAMP duration = ramp + hold; budget = (duration + margin*60) * 1000 (A-14 single point)
        assertThat(d.runDurationSeconds()).isEqualTo(180L);
        assertThat(d.budgetMarginMinutes()).isEqualTo(15);
        assertThat(d.budgetMs()).isEqualTo((180L + 15 * 60L) * 1000L);
    }

    @Test
    void budgetPreviewRidesRejectedDecisionAndUnknownModelYieldsZeroBudget() {
        setConfig("perf.budget-margin-minutes", "15");
        // No whitelist for this env → the decision is rejected, yet the budget preview
        // must still ride along so the summary card shows the honest dispatch window.
        PerfGateDecision rejected = perfAccessGate.evaluate(new PerfGateCheckInput(
                projectId, prodEnvId, 100, "SHADOW_TABLE", null,
                "CONSTANT_RPS", constantRpsConfig(300)));
        assertThat(rejected.passed()).isFalse();
        assertThat(rejected.runDurationSeconds()).isEqualTo(300L);
        assertThat(rejected.budgetMs()).isEqualTo((300L + 15 * 60L) * 1000L);

        // Legacy 5-arg callers (A-1 launch enforcement) declare no pressure → budget 0,
        // dispatcher falls back to the global timeout key (zero drift).
        PerfGateDecision legacy = perfAccessGate.evaluate(new PerfGateCheckInput(
                projectId, devEnvId, 100, "SHADOW_TABLE", null));
        assertThat(legacy.runDurationSeconds()).isZero();
        assertThat(legacy.budgetMs()).isZero();
    }

    private com.fasterxml.jackson.databind.JsonNode constantRpsConfig(int durationSeconds) {
        return new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(
                java.util.Map.of("targetRps", 50, "durationSeconds", durationSeconds));
    }


    private void setConfig(String key, String value) {
        cleanupConfig(key);
        configService.create(1L, new ConfigInput(key, value, "系统", "string", 0, "压测安全闸参数"));
    }

    private void restoreSeed(String key, String value) {
        cleanupConfig(key);
        configService.create(1L, new ConfigInput(key, value, "系统",
                key.contains("enabled") ? "bool" : (key.contains("hours") ? "int" : "string"),
                0, "生产压测审批有效期与闸参数（V136 种子）"));
    }

    private void addWhitelist(long envId, int maxConcurrency, List<String> modes) {
        perfWhitelistService.create(projectId, new PerfWhitelistInput(envId, maxConcurrency, modes));
    }

    private long approveThroughId(long pid, long envId, int concurrency, String mode) {
        long id = perfApprovalService.apply(pid, operatorId,
                new PerfApprovalInput(envId, concurrency, mode, "容量验收窗口"));
        perfApprovalService.approve(id, operatorId);
        return id;
    }

    private void approveThrough(long pid, long envId, int concurrency, String mode) {
        approveThroughId(pid, envId, concurrency, mode);
    }
}
