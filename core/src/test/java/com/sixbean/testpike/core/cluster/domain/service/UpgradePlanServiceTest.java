package com.sixbean.testpike.core.cluster.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cluster.api.dto.UpgradePlanCreateInput;
import com.sixbean.testpike.core.cluster.api.dto.UpgradePlanInfo;
import com.sixbean.testpike.core.cluster.api.dto.ReleaseRegisterInput;
import com.sixbean.testpike.core.cluster.api.dto.UpgradePlanStepView;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class UpgradePlanServiceTest {

    private static final String COOL_KEY = "cluster.upgrade-cooldown-seconds";

    @Autowired
    private UpgradePlanService planService;
    @Autowired
    private ClusterComponentService componentService;
    @Autowired
    private ReleaseManifestService manifestService;
    @Autowired
    private ConfigCacheHolder configCacheHolder;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final String N1 = "upl-t321-n1";
    private static final String N2 = "upl-t321-n2";
    private static final String FIXTURE_NODE_PREFIX = "upl-t321-%";
    private static final List<String> LEDGER_SNAPSHOT_COLUMNS = List.of(
            "node_id", "component_family", "current_version", "target_version",
            "requirement", "support_state", "state_reason", "source", "reported_at");

    /**
     * Snapshot of the live cluster ledger rows taken before each test. Real agents
     * keep upserting tp_cluster_component on the shared dev database and the fleet
     * scan can flip those rows to YELLOW at any moment, while plan preview reads the
     * whole ledger — so "the ledger holds only my seed rows" must be established, not
     * assumed. Otherwise preview/judgement tests fail depending on ambient cluster
     * activity (defect ledger of 2026-11, reproducible on a clean baseline too).
     */
    private List<Map<String, Object>> foreignLedgerRows = List.of();

    @BeforeEach
    void quarantineForeignLedgerRows() {
        String cols = String.join(", ", LEDGER_SNAPSHOT_COLUMNS);
        foreignLedgerRows = jdbcTemplate.queryForList(
                "SELECT " + cols + " FROM tp_cluster_component WHERE node_id NOT LIKE ?",
                FIXTURE_NODE_PREFIX);
        jdbcTemplate.update("DELETE FROM tp_cluster_component WHERE node_id NOT LIKE ?",
                FIXTURE_NODE_PREFIX);
        cleanFixtureOwned();
    }

    @AfterEach
    void cleanFixtureAndRestoreLedger() {
        cleanFixtureOwned();
        restoreForeignLedgerRows();
        configCacheHolder.remove(COOL_KEY);
    }

    /** Drop every row this class seeds, so a crashed earlier run leaves no residue. */
    private void cleanFixtureOwned() {
        cleanLedger();
        jdbcTemplate.update("DELETE FROM tp_upgrade_plan_step WHERE node_id LIKE 'upl-t321-%'");
        jdbcTemplate.execute("DELETE FROM tp_upgrade_plan WHERE operator = 't321-test'");
        jdbcTemplate.execute("DELETE FROM tp_release_manifest WHERE version LIKE '9.321.%'");
    }

    /**
     * Restore-snapshot write-back: ON CONFLICT DO NOTHING keeps a fresher heartbeat
     * row that the live agent re-created during the test in favour of the snapshot.
     */
    private void restoreForeignLedgerRows() {
        if (foreignLedgerRows.isEmpty()) {
            return;
        }
        String cols = String.join(", ", LEDGER_SNAPSHOT_COLUMNS);
        String placeholders = LEDGER_SNAPSHOT_COLUMNS.stream().map(c -> "?")
                .reduce((a, b) -> a + ", " + b).orElseThrow();
        for (Map<String, Object> row : foreignLedgerRows) {
            Object[] values = LEDGER_SNAPSHOT_COLUMNS.stream().map(row::get).toArray();
            jdbcTemplate.update("INSERT INTO tp_cluster_component (" + cols + ") VALUES ("
                    + placeholders + ") ON CONFLICT (node_id, component_family) DO NOTHING", values);
        }
        foreignLedgerRows = List.of();
    }

    private void cleanLedger() {
        jdbcTemplate.update("DELETE FROM tp_cluster_component WHERE node_id LIKE ?", FIXTURE_NODE_PREFIX);
    }

    
    private void seedNodeRuntime(String node, String current, String target) {
        componentService.upsert(node, "engine-runtime.node", current, "scan", ">=22.12", target);
    }

    private static UpgradePlanCreateInput enginePlan(List<String> engines, List<String> canary) {
        return new UpgradePlanCreateInput("engine", engines, canary, null, null, null,null,null, "t321-test");
    }


    @Test
    void create_rejectsUnknownScope() {
        ServiceException e = assertThrows(ServiceException.class,
                () -> planService.create(new UpgradePlanCreateInput("cluster", List.of(), null,
                        null, null, null,null,null, "t321-test")));
        assertTrue(e.getMessage().contains("engine") && e.getMessage().contains("node"),
                "scope 值域人话：engine / node，当前=" + e.getMessage());
    }

    @Test
    void create_engineScope_rejectsUnknownEngineCode() {
        ServiceException e = assertThrows(ServiceException.class,
                () -> planService.create(enginePlan(List.of("NOTAIRTEST"), null)));
        assertTrue(e.getMessage().contains("NOTAIRTEST"), e.getMessage());
        assertTrue(e.getMessage().contains("描述符") || e.getMessage().contains("引擎中心"), e.getMessage());
    }

    @Test
    void create_engineScope_requiresAtLeastOneEngine_nodeScope_forbids() {
        assertThrows(ServiceException.class, () -> planService.create(enginePlan(List.of(), null)));
        seedNodeRuntime(N1, "22.12", "24.0");
        assertThrows(ServiceException.class,
                () -> planService.create(new UpgradePlanCreateInput("node", List.of("MOBILEWRIGHT"),
                        null, null, null, null,null,null, "t321-test")));
    }

    @Test
    void create_rejectsBadBatchSize() {
        seedNodeRuntime(N1, "22.12", "24.0");
        assertThrows(ServiceException.class,
                () -> planService.create(new UpgradePlanCreateInput("engine",
                        List.of("MOBILEWRIGHT"), null, 0, null, null,null,null, "t321-test")));
    }

    @Test
    void create_rejectsUnknownManifestReference() {
        seedNodeRuntime(N1, "22.12", "24.0");
        ServiceException e = assertThrows(ServiceException.class,
                () -> planService.create(new UpgradePlanCreateInput("engine",
                        List.of("MOBILEWRIGHT"), null, null, null, "9.321.99",null,null, "t321-test")));
        assertTrue(e.getMessage().contains("9.321.99"), e.getMessage());
        manifestService.register(manifestInput("9.321.1"));
        assertNotNull(planService.create(new UpgradePlanCreateInput("engine",
                List.of("MOBILEWRIGHT"), null, null, null, "9.321.1",null,null, "t321-test")));
    }

    @Test
    void create_rejectsCanaryNodeOutsideUpgradableSet() {
        seedNodeRuntime(N1, "22.12", "24.0");
        ServiceException e = assertThrows(ServiceException.class,
                () -> planService.create(enginePlan(List.of("MOBILEWRIGHT"), List.of(N2))));
        assertTrue(e.getMessage().contains(N2) || e.getMessage().contains("灰度"), e.getMessage());
    }

    @Test
    void create_nothingUpgradable_humanRejectNoEmptyPlan() {
        seedNodeRuntime(N1, "24.0", "24.0");
        ServiceException e = assertThrows(ServiceException.class,
                () -> planService.create(enginePlan(List.of("MOBILEWRIGHT"), null)));
        assertTrue(e.getMessage().contains("无可升级"), e.getMessage());
        assertTrue(planService.listAll().stream().noneMatch(p -> "t321-test".equals(p.operator())),
                "拒创建禁留下半截计划行");
    }


    @Test
    void preview_fromLedgerYellowOnly_greenSkipped() {
        seedNodeRuntime(N1, "22.12", "24.0");
        seedNodeRuntime(N2, "24.0", "24.0");
        UpgradePlanInfo plan = planService.create(enginePlan(List.of("MOBILEWRIGHT"), null));
        assertEquals("PREVIEW", plan.status());

        List<UpgradePlanStepView> steps = planService.steps(plan.id());
        assertEquals(1, steps.size(), "只有台账 YELLOW 行进计划步：GREEN/追平禁冒充可升");
        UpgradePlanStepView step = steps.get(0);
        assertEquals(N1, step.nodeId());
        assertEquals("engine", step.kind());
        assertEquals("MOBILEWRIGHT", step.engineCode());
        assertEquals("batch", step.phase());
        assertEquals("22.12", step.currentVersion());
        assertEquals("24.0", step.targetVersion(), "目标=台账金版本基准引用（禁 payload 造第二基准）");
        assertEquals("PENDING", step.status());
        assertNullish(step.commandId(), "预览态未下发=command_id 空（下发归包③队列）");
    }

    @Test
    void strategyCoolSnapshot_keyDriven_withFallbackDefault() {
        seedNodeRuntime(N1, "22.12", "24.0");
        configCacheHolder.put(COOL_KEY, "45");
        UpgradePlanInfo plan = planService.create(enginePlan(List.of("MOBILEWRIGHT"), null));
        org.junit.jupiter.api.Assertions.assertEquals(45,
                extractCool(plan.strategy()), "策略快照 cool=键值");
        org.junit.jupiter.api.Assertions.assertEquals(1,
                extractBatchSize(plan.strategy()), "batchSize 默认 1 入快照");

        configCacheHolder.remove(COOL_KEY);
        UpgradePlanInfo fallback = planService.create(enginePlan(List.of("MOBILEWRIGHT"), null));
        org.junit.jupiter.api.Assertions.assertEquals(300, extractCool(fallback.strategy()),
                fallback.strategy());

        configCacheHolder.put(COOL_KEY, "abc");
        UpgradePlanInfo bad = planService.create(enginePlan(List.of("MOBILEWRIGHT"), null));
        org.junit.jupiter.api.Assertions.assertEquals(300, extractCool(bad.strategy()),
                bad.strategy());
        configCacheHolder.remove(COOL_KEY);
    }

    @Test
    void nodeScope_stepsUseExecutorAgentFamily() {
        componentService.upsert(N1, "executor-agent", "1.4.0", "scan", ">=1.3", "1.5.0");
        UpgradePlanInfo plan = planService.create(new UpgradePlanCreateInput("node", List.of(),
                null, null, null, null,null,null, "t321-test"));
        List<UpgradePlanStepView> steps = planService.steps(plan.id());
        assertEquals(1, steps.size());
        assertEquals("node", steps.get(0).kind());
        assertEquals("1.5.0", steps.get(0).targetVersion());
    }


    private UpgradePlanInfo previewPlan() {
        cleanLedger();
        seedNodeRuntime(N1, "22.12", "24.0");
        seedNodeRuntime(N2, "23.0", "24.0");
        return planService.create(enginePlan(List.of("MOBILEWRIGHT"), null));
    }

    @Test
    void start_transitionsAndAssignsDefaultCanary() {
        UpgradePlanInfo preview = previewPlan();
        UpgradePlanInfo running = planService.start(preview.id());
        assertEquals("RUNNING", running.status());

        Map<String, String> phases = phaseByNode(preview.id());
        assertEquals(N1, firstCanary(phases), "未指定灰度机=默认首台可升节点转 canary");
        assertEquals("canary", phases.get(N1));
        assertEquals("batch", phases.get(N2));

        ServiceException e = assertThrows(ServiceException.class, () -> planService.start(preview.id()));
        assertTrue(e.getMessage().contains("RUNNING") || e.getMessage().contains("预览"), e.getMessage());
    }

    @Test
    void pause_resume_abort_happyPathAndIllegalStartsRejected() {
        UpgradePlanInfo preview = previewPlan();
        assertThrows(ServiceException.class, () -> planService.pause(preview.id()));
        planService.start(preview.id());
        assertEquals("PAUSED", planService.pause(preview.id()).status());
        assertEquals("RUNNING", planService.resume(preview.id()).status());
        assertThrows(ServiceException.class, () -> planService.resume(preview.id()));
        assertEquals("ABORTED", planService.abort(preview.id()).status());
        assertThrows(ServiceException.class, () -> planService.abort(preview.id()));
    }

    @Test
    void complete_onlyFromRunning_brakeRecordsReason() {
        UpgradePlanInfo preview = previewPlan();
        assertThrows(ServiceException.class, () -> planService.complete(preview.id()));
        planService.start(preview.id());
        assertEquals("COMPLETED", planService.complete(preview.id()).status());

        UpgradePlanInfo running = previewPlan();
        planService.start(running.id());
        UpgradePlanInfo braked = planService.brake(running.id(),
                "金丝雀通过率 71% 低于下限 90%——自动刹车，灰度机已排入回滚");
        assertEquals("BRAKED", braked.status());
        assertNotNull(braked.brakeReason());
        assertTrue(braked.brakeReason().contains("71%"), "刹车归因人话落库（U3 证据链消费源）");
        assertThrows(ServiceException.class,
                () -> planService.brake(running.id(), "再来一次"));
    }

    private Map<String, String> phaseByNode(long planId) {
        Map<String, String> out = new java.util.TreeMap<>();
        for (UpgradePlanStepView s : planService.steps(planId)) {
            out.put(s.nodeId(), s.phase());
        }
        return out;
    }

    private static String firstCanary(Map<String, String> phases) {
        return phases.entrySet().stream()
                .filter(en -> "canary".equals(en.getValue()))
                .map(Map.Entry::getKey).findFirst().orElse("");
    }

    private static void assertNullish(Long value, String message) {
        assertEquals(null, value, message);
    }

    
    private static int extractCool(String strategyJson) {
        return extractInt(strategyJson, "cool");
    }

    private static int extractBatchSize(String strategyJson) {
        return extractInt(strategyJson, "batchSize");
    }

    private static int extractInt(String json, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + key + "\"\\s*:\\s*(-?\\d+)").matcher(json);
        assertTrue(m.find(), key + " 不在策略快照：" + json);
        return Integer.parseInt(m.group(1));
    }

 
    private static ReleaseRegisterInput manifestInput(String version) {
        return new ReleaseRegisterInput(version,
                "[{\"component\":\"executor-agent\",\"support\":\">=1.3\",\"pinned\":\"1.5.0\"}]",
                "[]", null, null, null, "manual",
                "[{\"component_family\":\"executor-agent\",\"url\":"
                        + "\"https://github.com/testpike/releases/e-1.5.0.zip\",\"sha256\":\""
                        + "1".repeat(64) + "\",\"size\":1}]",
                null);
    }
}
