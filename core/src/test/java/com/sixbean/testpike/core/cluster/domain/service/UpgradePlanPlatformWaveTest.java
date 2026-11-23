package com.sixbean.testpike.core.cluster.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cluster.api.dto.ReleaseManifestInfo;
import com.sixbean.testpike.core.cluster.api.dto.ReleaseRegisterInput;
import com.sixbean.testpike.core.cluster.api.dto.UpgradePlanCreateInput;
import com.sixbean.testpike.core.cluster.api.dto.UpgradePlanInfo;
import com.sixbean.testpike.core.cluster.api.dto.UpgradePlanStepView;
import com.sixbean.testpike.core.cluster.domain.component.WavePlanGenerator;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Platform one-click wave plan (scope=platform) segment test against the dev
 * database — no mocks: the create gate, the manifest completeness gate and the
 * persisted wave order must all hold on real rows.
 * <p>Acceptance face (design 8A.4): platform scope is accepted; engine codes are
 * forbidden for platform plans; a manifest version is the upgrade authority
 * (missing = human-worded rejection, the plan never guesses a target); steps
 * carry component_family + wave_seq in the dependency-derived order (managed
 * first, core leaves last); missing pins / missing artifacts reject naming the
 * exact family; an unpinned executor-agent skips that wave instead of rejecting
 * the plan; GREEN ledger rows never become steps; the rollback order is the
 * persisted wave_seq reversed (core first back, agent group last).
 */
@SpringBootTest(classes = CoreApplication.class)
class UpgradePlanPlatformWaveTest {

    private static final String NODE = "wave-t355b-n1";
    private static final String OPERATOR = "t355b-test";
    private static final String SECOND_REVIEWER = "t355b-reviewer";
    private static final String DUAL_REVIEW_KEY = "release.production.dual-review";

    @Autowired
    private UpgradePlanService planService;
    @Autowired
    private ClusterComponentService componentService;
    @Autowired
    private ReleaseManifestService manifestService;
    @Autowired
    private UpgradePipelineAdvanceService advanceService;
    @Autowired
    private ConfigCacheHolder configCacheHolder;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void cleanAll() {
        configCacheHolder.remove(DUAL_REVIEW_KEY);
        List<Long> planIds = jdbcTemplate.queryForList(
                "SELECT id FROM tp_upgrade_plan WHERE operator = ?", Long.class, OPERATOR);
        for (Long id : planIds) {
            for (String event : List.of("UPGRADE_STARTED", "CANARY_FAILED", "ROLLBACK_DONE")) {
                jdbcTemplate.update("DELETE FROM tp_notification WHERE event_type = ?"
                                + " AND dedup_key LIKE ?", event, event + ":" + id + ":%");
            }
        }
        jdbcTemplate.update("DELETE FROM tp_upgrade_plan_step WHERE node_id = ?", NODE);
        jdbcTemplate.update("DELETE FROM tp_upgrade_plan WHERE operator = ?", OPERATOR);
        jdbcTemplate.update("DELETE FROM tp_cluster_component WHERE node_id = ?", NODE);
        jdbcTemplate.update("DELETE FROM tp_cluster_command WHERE operator = ?", OPERATOR);
        jdbcTemplate.update("DELETE FROM tp_release_manifest WHERE version LIKE '9.355.%'");
    }

    // ---------- helpers ----------

    /** Ledger: every wave family YELLOW on one node (target ahead of current). */
    private void seedAllFamiliesYellow() {
        for (String family : WavePlanGenerator.WAVE_ORDER) {
            componentService.upsert(NODE, family, "1.0.0", "scan", ">=1.0", "9.355.1");
        }
    }

    /** Matrix pinning the six release families (+ optionally executor-agent). */
    private static String matrixJson(boolean withAgent) {
        List<String> items = new ArrayList<>();
        for (String family : List.of("core", "admin", "api", "ai-service", "frontend", "mock-server")) {
            items.add(pinItem(family));
        }
        if (withAgent) {
            items.add(pinItem("executor-agent"));
        }
        return "[" + String.join(",", items) + "]";
    }

    private static String pinItem(String family) {
        return "{\"component\":\"" + family + "\",\"support\":\">=1.0\",\"pinned\":\"9.355.1\"}";
    }

    private static String artifactsJson(boolean withAgent) {
        List<String> items = new ArrayList<>();
        for (String family : List.of("core", "admin", "api", "ai-service", "frontend", "mock-server")) {
            items.add(artifact(family));
        }
        if (withAgent) {
            items.add(artifact("executor-agent"));
        }
        return "[" + String.join(",", items) + "]";
    }

    private static String artifact(String family) {
        return "{\"component_family\":\"" + family + "\",\"url\":\"https://github.com/testpike/"
                + family + "-9.355.1.jar\",\"sha256\":\"" + "1".repeat(64) + "\",\"size\":1}";
    }

    private static ReleaseRegisterInput registerInput(String version, String matrix, String artifacts) {
        return new ReleaseRegisterInput(version, matrix, "[]", null, null, null, "manual", artifacts, null);
    }

    private static UpgradePlanCreateInput platformPlan(String manifestVersion) {
        return new UpgradePlanCreateInput("platform", List.of(), null, null, null,
                manifestVersion, null, null, OPERATOR);
    }

    /** Production-declared platform plan (decision E: the plan declares its
     * production scope + maintenance window at creation; the strictness of the
     * start gate is a hot K8 key, never a hardcoded policy). */
    private UpgradePlanCreateInput productionPlatformPlan(String window) {
        return new UpgradePlanCreateInput("platform", List.of(), null, null, null,
                "9.355.1", Boolean.TRUE, window, OPERATOR);
    }

    private UpgradePlanInfo createProductionPlan(String window) {
        manifestService.register(registerInput("9.355.1", matrixJson(true), artifactsJson(true)));
        seedAllFamiliesYellow();
        return planService.create(productionPlatformPlan(window));
    }

    // ---------- production dual-review gate (decision E, pack D back half) ----------

    @Test
    void start_dualReviewStrict_productionPlanWithoutWindow_rejects() {
        UpgradePlanInfo plan = createProductionPlan(null);
        configCacheHolder.put(DUAL_REVIEW_KEY, "1");
        ServiceException e = assertThrows(ServiceException.class, () -> planService.start(plan.id()));
        assertTrue(e.getMessage().contains("维护窗口"),
                "开严+生产申报缺维护窗口=人话拒启动，当前=" + e.getMessage());
    }

    @Test
    void start_dualReviewStrict_productionPlanUnreviewed_rejects() {
        UpgradePlanInfo plan = createProductionPlan("2026-10-14 02:00–02:30");
        configCacheHolder.put(DUAL_REVIEW_KEY, "1");
        ServiceException e = assertThrows(ServiceException.class, () -> planService.start(plan.id()));
        assertTrue(e.getMessage().contains("复核"),
                "开严+已申报窗口但未第二人复核=拒启动，当前=" + e.getMessage());
    }

    @Test
    void confirmDualReview_bySecondPerson_thenStart_running() {
        UpgradePlanInfo plan = createProductionPlan("2026-10-14 02:00–02:30");
        configCacheHolder.put(DUAL_REVIEW_KEY, "1");
        UpgradePlanInfo reviewed = planService.confirmDualReview(plan.id(), SECOND_REVIEWER);
        assertEquals(SECOND_REVIEWER, reviewed.reviewedBy(),
                "复核人落列=第二人身份，start 闸消费此列");
        UpgradePlanInfo running = planService.start(plan.id());
        assertEquals("RUNNING", running.status());
    }

    @Test
    void confirmDualReview_selfReview_rejectsDualReviewEssence() {
        UpgradePlanInfo plan = createProductionPlan("2026-10-14 02:00–02:30");
        configCacheHolder.put(DUAL_REVIEW_KEY, "1");
        ServiceException e = assertThrows(ServiceException.class,
                () -> planService.confirmDualReview(plan.id(), OPERATOR));
        assertTrue(e.getMessage().contains("本人"),
                "双人复核=复核人不得是发起人本人，当前=" + e.getMessage());
    }

    @Test
    void start_dualReviewOff_productionPlan_startsPermissively() {
        UpgradePlanInfo plan = createProductionPlan(null);
        // key absent = default 0 (permissive, decision E default; hot key flips strictness)
        UpgradePlanInfo running = planService.start(plan.id());
        assertEquals("RUNNING", running.status(),
                "开关关=非生产口径直接启动（默认宽松，禁硬编码开严）");
    }

    @Test
    void create_productionFieldsRoundTrip_throughInfoView() {
        UpgradePlanInfo plan = createProductionPlan("2026-10-14 02:00–02:30");
        assertEquals(Boolean.TRUE, plan.productionScope(),
                "生产申报随计划视图回读（前端复核/窗口展示消费位）");
        assertEquals("2026-10-14 02:00–02:30", plan.maintenanceWindow());
        assertNull(plan.reviewedBy(), "未复核=空，start 闸据此拦");
    }

    // ---------- create gate ----------

    @Test
    void create_platformScope_rejectsMissingManifestAuthority() {
        seedAllFamiliesYellow();
        ServiceException e = assertThrows(ServiceException.class,
                () -> planService.create(platformPlan(null)));
        assertTrue(e.getMessage().contains("发布清单") || e.getMessage().contains("权威"),
                "platform 缺 manifestVersion=权威缺失人话拒，当前=" + e.getMessage());
    }

    @Test
    void create_platformScope_forbidsEngineCodes() {
        seedAllFamiliesYellow();
        manifestService.register(registerInput("9.355.1", matrixJson(true), artifactsJson(true)));
        ServiceException e = assertThrows(ServiceException.class,
                () -> planService.create(new UpgradePlanCreateInput("platform",
                        List.of("MOBILEWRIGHT"), null, null, null, "9.355.1",
                        null, null, OPERATOR)));
        assertTrue(e.getMessage().contains("引擎"), e.getMessage());
    }

    @Test
    void create_platformScope_rejectsIncompleteMatrix_namingMissingFamily() {
        seedAllFamiliesYellow();
        // Matrix without the "core" pin: the plan has no authority for core.
        String partial = matrixJson(true).replace(pinItem("core") + ",", "");
        manifestService.register(registerInput("9.355.2", partial, artifactsJson(true)));
        ServiceException e = assertThrows(ServiceException.class,
                () -> planService.create(platformPlan("9.355.2")));
        assertTrue(e.getMessage().contains("core"),
                "缺钉定必须点名部件族，当前=" + e.getMessage());
    }

    @Test
    void create_platformScope_rejectsPinnedFamilyWithoutArtifact() {
        seedAllFamiliesYellow();
        // Legacy shape: rows registered before the register gate could
        // carry a pin with no artifact. Seeded straight into the table (the
        // register API now rejects this shape upstream — the plan gate is the
        // defence-in-depth backstop for existing rows).
        String noAdmin = artifactsJson(true).replace(artifact("admin") + ",", "");
        jdbcTemplate.update("""
                INSERT INTO tp_release_manifest (version, component_matrix, breaking_changes,
                    source, artifacts)
                VALUES ('9.355.3', ?::jsonb, '[]'::jsonb, 'manual', ?::jsonb)
                """, matrixJson(true), noAdmin);
        ServiceException e = assertThrows(ServiceException.class,
                () -> planService.create(platformPlan("9.355.3")));
        assertTrue(e.getMessage().contains("admin"),
                "钉定缺工件必须点名部件族，当前=" + e.getMessage());
    }

    // ---------- wave plan generation ----------

    @Test
    void platformPlan_stepsPersistedInWaveOrder_withFamilyAndSeq() {
        seedAllFamiliesYellow();
        manifestService.register(registerInput("9.355.1", matrixJson(true), artifactsJson(true)));
        UpgradePlanInfo plan = planService.create(platformPlan("9.355.1"));
        assertEquals("PREVIEW", plan.status());
        assertEquals("platform", plan.scope());

        List<UpgradePlanStepView> steps = planService.steps(plan.id());
        List<String> families = steps.stream().map(UpgradePlanStepView::componentFamily).toList();
        assertEquals(List.of("executor-agent", "frontend", "api", "admin", "ai-service", "mock-server", "core"),
                families, "波次序=被管者先升、控制面最后离场");
        for (UpgradePlanStepView step : steps) {
            assertEquals("platform", step.kind());
            assertNull(step.engineCode(), "平台步不挂引擎 code");
            assertEquals(WavePlanGenerator.waveSeq(step.componentFamily()),
                    step.waveSeq().intValue(), "wave_seq=单源 WAVE_ORDER 位次（禁消费端二谱）");
            assertEquals("9.355.1", step.targetVersion(), "目标=清单钉定版本（禁 payload 造第二基准）");
            assertEquals("PENDING", step.status());
        }
    }

    @Test
    void platformPlan_rollbackOrderIsPersistedWaveSeqReversed() {
        seedAllFamiliesYellow();
        manifestService.register(registerInput("9.355.1", matrixJson(true), artifactsJson(true)));
        UpgradePlanInfo plan = planService.create(platformPlan("9.355.1"));
        List<UpgradePlanStepView> forward = planService.steps(plan.id());
        List<String> rollbackFamilies = forward.reversed().stream()
                .map(UpgradePlanStepView::componentFamily).toList();
        assertEquals("core", rollbackFamilies.get(0), "回滚 core 最先回");
        assertEquals("executor-agent", rollbackFamilies.get(rollbackFamilies.size() - 1),
                "agent 组最后回");
    }

    // ---------- one-click rollback plan (target = ledger last-known-good) ----------

    /** Ledger: every wave family already sits on 9.355.1, last-good = 1.0.0. */
    private void seedRolledBackState(String lastGood) {
        for (String family : WavePlanGenerator.WAVE_ORDER) {
            componentService.upsert(NODE, family, "9.355.1", "scan", ">=1.0", "9.355.1");
        }
        if (lastGood != null) {
            jdbcTemplate.update("UPDATE tp_cluster_component SET last_known_good = ?"
                    + " WHERE node_id = ? AND last_known_good IS NULL", lastGood, NODE);
        }
    }

    @Test
    void createRollbackPlan_targetsLastKnownGood_walksWaveSeqReversed() {
        seedRolledBackState("1.0.0");
        UpgradePlanInfo rb = planService.createRollbackPlan(OPERATOR);
        assertEquals("platform", rb.scope());
        assertEquals("PREVIEW", rb.status());
        assertTrue(rb.strategy().contains("rollback"), "方向标落 strategy json，当前=" + rb.strategy());

        List<UpgradePlanStepView> steps = planService.steps(rb.id());
        List<String> families = steps.stream().map(UpgradePlanStepView::componentFamily).toList();
        assertEquals(List.of("core", "mock-server", "ai-service", "admin", "api", "frontend", "executor-agent"),
                families, "读取层按方向反序=core 最先回、agent 组最后回");
        for (UpgradePlanStepView step : steps) {
            assertEquals("1.0.0", step.targetVersion(), "回滚目标=台账 last-known-good（禁猜版本）");
            assertEquals("9.355.1", step.currentVersion());
            assertEquals(WavePlanGenerator.waveSeq(step.componentFamily()), step.waveSeq().intValue(),
                    "持久层仍存正向 wave_seq（单源禁二谱，反序只在读取层按方向标走）");
        }

        // Persistence keeps the forward order (one spectrum on disk).
        List<String> persisted = jdbcTemplate.queryForList(
                "SELECT component_family FROM tp_upgrade_plan_step"
                        + " WHERE plan_id = ? ORDER BY wave_seq", String.class, rb.id());
        assertEquals(List.of("executor-agent", "frontend", "api", "admin", "ai-service", "mock-server", "core"),
                persisted);
    }

    @Test
    void createRollbackPlan_skipsRowsWithoutLastKnownGood_neverFabricatesTarget() {
        seedRolledBackState("1.0.0");
        jdbcTemplate.update("UPDATE tp_cluster_component SET last_known_good = NULL"
                + " WHERE node_id = ? AND component_family = 'admin'", NODE);
        // No recorded good version = that row never landed a healthy upgrade
        // receipt; it is excluded from rollback rather than given a guessed
        // target (fail-closed on the version, not on the whole plan).
        UpgradePlanInfo rb = planService.createRollbackPlan(OPERATOR);
        List<UpgradePlanStepView> steps = planService.steps(rb.id());
        assertTrue(steps.stream().noneMatch(s -> "admin".equals(s.componentFamily())),
                "无 last-known-good 的行不排入回滚（禁猜目标）");
        assertEquals(List.of("core", "mock-server", "ai-service", "api", "frontend", "executor-agent"),
                steps.stream().map(UpgradePlanStepView::componentFamily).toList());
        for (UpgradePlanStepView step : steps) {
            assertEquals("1.0.0", step.targetVersion());
        }
    }

    @Test
    void createRollbackPlan_rejectsNothingToRollBack() {
        // Already on the last-good version everywhere = no rollback target exists.
        for (String family : WavePlanGenerator.WAVE_ORDER) {
            componentService.upsert(NODE, family, "1.0.0", "scan", ">=1.0", "9.355.1");
        }
        jdbcTemplate.update("UPDATE tp_cluster_component SET last_known_good = '1.0.0'"
                + " WHERE node_id = ?", NODE);
        ServiceException e = assertThrows(ServiceException.class,
                () -> planService.createRollbackPlan(OPERATOR));
        assertTrue(e.getMessage().contains("无可回退") || e.getMessage().contains("回退"),
                "空回滚集人话拒，当前=" + e.getMessage());
    }

    @Test
    void platformPlan_skipsAgentWaveWhenNoAgentPin() {
        // No agent pin in the matrix = that wave is skipped, not a rejection
        // (design 8A.4: executor-agent upgrades follow the manifest interval,
        // an announcement-only release legitimately ships no agent package).
        for (String family : WavePlanGenerator.WAVE_ORDER) {
            if ("executor-agent".equals(family)) {
                continue;
            }
            componentService.upsert(NODE, family, "1.0.0", "scan", ">=1.0", "9.355.1");
        }
        manifestService.register(registerInput("9.355.4", matrixJson(false), artifactsJson(false)));
        UpgradePlanInfo plan = planService.create(platformPlan("9.355.4"));
        List<String> families = planService.steps(plan.id()).stream()
                .map(UpgradePlanStepView::componentFamily).toList();
        assertEquals(List.of("frontend", "api", "admin", "ai-service", "mock-server", "core"), families,
                "无 agent 钉定=跳该波非拒整个计划");
    }

    @Test
    void platformPlan_greenLedgerRowsNeverBecomeSteps() {
        // Everything already at the pinned version = nothing to do.
        for (String family : WavePlanGenerator.WAVE_ORDER) {
            componentService.upsert(NODE, family, "9.355.1", "scan", ">=1.0", "9.355.1");
        }
        manifestService.register(registerInput("9.355.1", matrixJson(true), artifactsJson(true)));
        ServiceException e = assertThrows(ServiceException.class,
                () -> planService.create(platformPlan("9.355.1")));
        assertTrue(e.getMessage().contains("无可升级"), e.getMessage());
        assertTrue(planService.listAll().stream().noneMatch(p -> OPERATOR.equals(p.operator())),
                "拒创建禁留半截计划行");
    }

    // ---------- last-known-good write-back on command success ----------

    @Test
    void advance_stepCommandSuccessStampsLastKnownGoodOnLedger() {
        seedAllFamiliesYellow();
        manifestService.register(registerInput("9.355.1", matrixJson(true), artifactsJson(true)));
        UpgradePlanInfo plan = planService.create(platformPlan("9.355.1"));
        planService.start(plan.id());

        // Simulate one landed platform step: a successful command already on the
        // queue, step RUNNING with command_id back-filled (dispatch wiring is
        // the L4 package; this segment pins the receipt->ledger write-back only).
        Long cmdId = jdbcTemplate.queryForObject(
                "INSERT INTO tp_cluster_command (command_type, target_type, target_id, payload,"
                        + " status, expires_at, operator) VALUES ('platform-service-upgrade',"
                        + " 'executor', 1, '{}'::jsonb, 3, now() + interval '10 minutes', ?)"
                        + " RETURNING id", Long.class, OPERATOR);
        jdbcTemplate.update("UPDATE tp_upgrade_plan_step SET status = 'RUNNING', command_id = ?"
                + " WHERE plan_id = ? AND component_family = 'core'", cmdId, plan.id());

        advanceService.advanceOnce();

        assertEquals("SUCCESS", planService.steps(plan.id()).stream()
                .filter(s -> "core".equals(s.componentFamily())).findFirst().orElseThrow()
                .status(), "回执成功步落 SUCCESS");
        String good = jdbcTemplate.queryForObject(
                "SELECT last_known_good FROM tp_cluster_component WHERE node_id = ?"
                        + " AND component_family = 'core'", String.class, NODE);
        assertEquals("9.355.1", good, "成功回执=该族该节点写入 last-known-good（回滚目标源）");
        String untouched = jdbcTemplate.queryForObject(
                "SELECT last_known_good FROM tp_cluster_component WHERE node_id = ?"
                        + " AND component_family = 'frontend'", String.class, NODE);
        assertNull(untouched, "未落回执的族不冒充健康版本");
    }

    // ---------- pure rule anchors (single source, no DB) ----------

    @Test
    void waveOrder_managedFirstControlPlaneLast_engineNotPlatformWave() {
        assertEquals("executor-agent", WavePlanGenerator.WAVE_ORDER.get(0));
        assertEquals("core", WavePlanGenerator.WAVE_ORDER.get(WavePlanGenerator.WAVE_ORDER.size() - 1));
        assertTrue(!WavePlanGenerator.platformFamily("engine-runtime.node"),
                "engine 运行时走既有 engine-upgrade 链，不是平台波步");
        assertEquals(1, WavePlanGenerator.waveSeq("frontend"));
    }

    @Test
    void stepViewWireShapeCarriesWaveColumns() throws Exception {
        // The wire view must expose the two wave columns (admin bridge maps
        // same-name fields; consumers sort on the persisted seq, never re-derive).
        assertEquals(13, UpgradePlanStepView.class.getRecordComponents().length);
        assertNotNull(UpgradePlanStepView.class.getDeclaredMethod("componentFamily"));
        assertNotNull(UpgradePlanStepView.class.getDeclaredMethod("waveSeq"));
        assertNull(manifestService.findByVersion("0.0.0-absent"),
                "sanity: finder returns null for an absent version");
    }
}
