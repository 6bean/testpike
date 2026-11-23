package com.sixbean.testpike.core.cluster.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cluster.api.dto.ReleaseRegisterInput;
import com.sixbean.testpike.core.cluster.api.dto.UpgradePlanCreateInput;
import com.sixbean.testpike.core.cluster.api.dto.UpgradePlanInfo;
import com.sixbean.testpike.core.cluster.api.dto.UpgradePlanStepView;
import com.sixbean.testpike.core.cluster.domain.component.WavePlanGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Platform wave advance (the pipeline dispatches platform plans): the pipeline must dispatch platform
 * plans wave by wave over the enroll-token channel (consumer = installkit
 * service-agent, 03 §2.9), never through the executor engine path and never
 * via the canary/baselineRef gates that only make sense for engine upgrades.
 * <p>Acceptance face pinned here:
 * 1) the first wave (executor-agent) dispatches agent-upgrade with the
 * manifest artifact (url+sha256+target_version) against the token target;
 * only that wave is dispatched while later waves stay PENDING (no wave
 * jumping);
 * 2) waves land strictly in the persisted wave_seq order and the plan
 * completes only after the last (core) wave lands;
 * 3) a rollback plan walks the order reversed and issues
 * platform-service-rollback pinned to the ledger last-known-good, with the
 * artifact resolved from that old version's registered manifest;
 * 4) a rollback target with no registered manifest = fail-closed human-worded
 * brake, zero commands queued (never guess a package);
 * 5) direction detection lives in WavePlanGenerator as the single source
 * (delegation, no second spectrum).
 * <p>No mocks: real dev DB rows end to end (ledger, manifest registry, token,
 * plan, command queue), matching the pipeline's production shape.
 */
@SpringBootTest(classes = CoreApplication.class)
class UpgradePipelinePlatformAdvanceTest {

    private static final String OPERATOR = "t355f-test";
    private static final String REMARK = "t355f-fixture";
    private static final String FORWARD_VERSION = "9.355.1";
    private static final String GOOD_VERSION = "9.354.0";

    @Autowired
    private UpgradePlanService planService;
    @Autowired
    private UpgradePipelineAdvanceService advanceService;
    @Autowired
    private ReleaseManifestService manifestService;
    @Autowired
    private ClusterComponentService componentService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void cleanAll() {
        String tokenNodeSubquery =
                "SELECT 'token:' || id FROM tp_cluster_enroll_token WHERE remark = '" + REMARK + "'";
        List<Long> planIds = jdbcTemplate.queryForList(
                "SELECT id FROM tp_upgrade_plan WHERE operator = ?", Long.class, OPERATOR);
        for (Long id : planIds) {
            for (String event : List.of("UPGRADE_STARTED", "CANARY_FAILED", "ROLLBACK_DONE",
                    "BATCH_DONE")) {
                jdbcTemplate.update("DELETE FROM tp_notification WHERE event_type = ?"
                                + " AND dedup_key LIKE ?", event, event + ":" + id + ":%");
            }
        }
        jdbcTemplate.update("DELETE FROM tp_upgrade_plan_step WHERE plan_id IN ("
                + "SELECT id FROM tp_upgrade_plan WHERE operator = ?)", OPERATOR);
        jdbcTemplate.update("DELETE FROM tp_upgrade_plan WHERE operator = ?", OPERATOR);
        jdbcTemplate.update("DELETE FROM tp_cluster_command WHERE operator = ?", OPERATOR);
        jdbcTemplate.update("DELETE FROM tp_cluster_component WHERE node_id IN ("
                + tokenNodeSubquery + ")");
        jdbcTemplate.update("DELETE FROM tp_cluster_component WHERE node_id = 'manual-node-n1'");
        jdbcTemplate.update("DELETE FROM tp_cluster_enroll_token WHERE remark = ?", REMARK);
        jdbcTemplate.update("DELETE FROM tp_release_manifest WHERE version IN (?, ?)",
                FORWARD_VERSION, GOOD_VERSION);
    }

    // ---------- fixtures ----------

    /** Enroll-token ledger node in the production shape: node_id = "token:<id>". */
    private long newTokenId() {
        String digest = String.format("%016x", System.nanoTime()) + "0".repeat(48);
        return jdbcTemplate.queryForObject(
                "INSERT INTO tp_cluster_enroll_token (token_digest, role, max_uses, status,"
                        + " expires_at, remark) VALUES (?, 'platform', 1, 1,"
                        + " now() + interval '1 hour', ?) RETURNING id",
                Long.class, digest, REMARK);
    }

    /** Ledger: every wave family YELLOW on the token node (upgrade authority). */
    private void seedLedger(long tokenId, String current, String target) {
        String node = "token:" + tokenId;
        for (String family : WavePlanGenerator.WAVE_ORDER) {
            componentService.upsert(node, family, current, "scan", ">=1.0", target);
        }
    }

    private static String matrixJson(String pinned) {
        List<String> items = new ArrayList<>();
        for (String family : WavePlanGenerator.WAVE_ORDER) {
            items.add("{\"component\":\"" + family + "\",\"support\":\">=1.0\",\"pinned\":"
                    + "\"" + pinned + "\"}");
        }
        return "[" + String.join(",", items) + "]";
    }

    private static String artifactsJson(String version) {
        List<String> items = new ArrayList<>();
        for (String family : WavePlanGenerator.WAVE_ORDER) {
            items.add("{\"component_family\":\"" + family + "\",\"url\":\"https://github.com"
                    + "/testpike/" + family + "-" + version + ".jar\",\"sha256\":\""
                    + "1".repeat(64) + "\",\"size\":1}");
        }
        return "[" + String.join(",", items) + "]";
    }

    private void registerManifest(String version) {
        manifestService.register(new ReleaseRegisterInput(version, matrixJson(version),
                "[]", null, null, null, "manual", artifactsJson(version), null));
    }

    private UpgradePlanInfo startedForwardPlan(long tokenId) {
        UpgradePlanInfo plan = planService.create(new UpgradePlanCreateInput("platform",
                List.of(), null, null, null, FORWARD_VERSION, null, null, OPERATOR));
        return planService.start(plan.id());
    }

    private List<Map<String, Object>> commands() {
        return jdbcTemplate.queryForList(
                "SELECT id, command_type, target_type, target_id, payload::text AS payload"
                        + " FROM tp_cluster_command WHERE operator = ? ORDER BY id", OPERATOR);
    }

    private UpgradePlanStepView stepOf(long planId, String family) {
        return planService.steps(planId).stream()
                .filter(s -> family.equals(s.componentFamily()))
                .findFirst().orElseThrow(() -> new AssertionError("缺波次步：" + family));
    }

    /** Land every currently RUNNING wave: mark its queued commands successful. */
    private void landRunningWave() {
        jdbcTemplate.update("UPDATE tp_cluster_command SET status = 3 WHERE operator = ?"
                + " AND status = 0", OPERATOR);
    }

    // ---------- forward wave advance ----------

    @Test
    void advance_platformPlan_dispatchesFirstWaveOverTokenTarget_withManifestArtifact() {
        long tokenId = newTokenId();
        registerManifest(FORWARD_VERSION);
        seedLedger(tokenId, "1.0.0", FORWARD_VERSION);
        UpgradePlanInfo plan = startedForwardPlan(tokenId);

        advanceService.advanceOnce();

        List<Map<String, Object>> cmds = commands();
        assertEquals(1, cmds.size(), "首波只派 executor-agent 一条，禁跳波派发");
        Map<String, Object> cmd = cmds.get(0);
        assertEquals("agent-upgrade", cmd.get("command_type"));
        assertEquals("enroll-token", cmd.get("target_type"),
                "平台/agent 命令消费方是主机 service-agent，必须走令牌通道");
        assertEquals(tokenId, ((Number) cmd.get("target_id")).longValue());
        String payload = (String) cmd.get("payload");
        // jsonb ::text normalizes spacing — match on the normalized shape.
        assertTrue(payload.contains("\"target_version\": \"" + FORWARD_VERSION + "\""), payload);
        assertTrue(payload.contains("executor-agent-" + FORWARD_VERSION + ".jar"), payload);
        assertTrue(payload.contains("sha256"), payload);

        UpgradePlanStepView agentStep = stepOf(plan.id(), "executor-agent");
        assertEquals("RUNNING", agentStep.status());
        assertNotNull(agentStep.commandId(), "派发回填 command_id（回执轮询锚）");
        assertEquals("PENDING", stepOf(plan.id(), "core").status(), "后续波保持待派");
        assertEquals("RUNNING", planService.get(plan.id()).status());
    }

    @Test
    void advance_platformWaves_walkAgentToCore_inPersistedWaveOrder_thenComplete() {
        long tokenId = newTokenId();
        registerManifest(FORWARD_VERSION);
        seedLedger(tokenId, "1.0.0", FORWARD_VERSION);
        UpgradePlanInfo plan = startedForwardPlan(tokenId);

        for (String family : WavePlanGenerator.WAVE_ORDER) {
            advanceService.advanceOnce();
            List<Map<String, Object>> cmds = commands();
            Map<String, Object> latest = cmds.get(cmds.size() - 1);
            String expectedType = "executor-agent".equals(family)
                    ? "agent-upgrade" : "platform-service-upgrade";
            assertEquals(expectedType, latest.get("command_type"),
                    "第 " + cmds.size() + " 条命令应派波 " + family);
            assertTrue(((String) latest.get("payload")).contains("\"service\": \"" + family + "\"")
                    || "executor-agent".equals(family),
                    "platform-service 命令必带服务码=" + latest.get("payload"));
            landRunningWave();
        }
        assertEquals(WavePlanGenerator.WAVE_ORDER.size(), commands().size());

        // receipts land + no wave left -> plan completes on the next tick
        advanceService.advanceOnce();
        assertEquals("COMPLETED", planService.get(plan.id()).status());
        for (UpgradePlanStepView step : planService.steps(plan.id())) {
            assertEquals("SUCCESS", step.status(), step.componentFamily() + " 步应落成功");
        }
    }

    @Test
    void advance_platformRollback_reversedWaves_withOldManifest_artifactPinnedToLastGood() {
        long tokenId = newTokenId();
        registerManifest(FORWARD_VERSION);
        registerManifest(GOOD_VERSION);
        seedLedger(tokenId, FORWARD_VERSION, FORWARD_VERSION);
        jdbcTemplate.update("UPDATE tp_cluster_component SET last_known_good = ?"
                        + " WHERE node_id = 'token:' || ?", GOOD_VERSION, tokenId);
        UpgradePlanInfo rb = planService.createRollbackPlan(OPERATOR);
        planService.start(rb.id());

        advanceService.advanceOnce();

        List<Map<String, Object>> cmds = commands();
        assertEquals(1, cmds.size());
        Map<String, Object> cmd = cmds.get(0);
        assertEquals("platform-service-rollback", cmd.get("command_type"),
                "回滚首波=core（波序反转，控制面最后升最先降）");
        assertTrue(((String) cmd.get("payload")).contains("\"service\": \"core\""), (String) cmd.get("payload"));
        assertTrue(((String) cmd.get("payload")).contains("\"target_version\": \"" + GOOD_VERSION + "\""),
                "回滚版本=台账 last-known-good（禁猜）");
        assertTrue(((String) cmd.get("payload")).contains("core-" + GOOD_VERSION + ".jar"),
                "回滚工件=旧版本登记清单里的包（供给链闭环）");
        assertEquals("enroll-token", cmd.get("target_type"));
    }

    @Test
    void advance_platformRollback_unregisteredOldVersion_brakesFailClosed_noCommandQueued() {
        long tokenId = newTokenId();
        registerManifest(FORWARD_VERSION);
        seedLedger(tokenId, FORWARD_VERSION, FORWARD_VERSION);
        jdbcTemplate.update("UPDATE tp_cluster_component SET last_known_good = '0.0.9-no-manifest'"
                + " WHERE node_id = 'token:' || ?", tokenId);
        UpgradePlanInfo rb = planService.createRollbackPlan(OPERATOR);
        planService.start(rb.id());

        advanceService.advanceOnce();

        UpgradePlanInfo after = planService.get(rb.id());
        assertEquals("BRAKED", after.status());
        assertNotNull(after.brakeReason());
        assertTrue(after.brakeReason().contains("登记") || after.brakeReason().contains("清单"),
                "刹车归因人话点名缺登记件，当前：" + after.brakeReason());
        assertTrue(commands().isEmpty(), "缺登记件禁猜包派发——零命令入队");
    }

    @Test
    void advance_platformWave_missingTokenAddress_brakesHumanWords_neverHalfDispatches() {
        registerManifest(FORWARD_VERSION);
        for (String family : WavePlanGenerator.WAVE_ORDER) {
            componentService.upsert("manual-node-n1", family, "1.0.0", "scan", ">=1.0",
                    FORWARD_VERSION);
        }
        UpgradePlanInfo plan = planService.create(new UpgradePlanCreateInput("platform",
                List.of(), null, null, null, FORWARD_VERSION, null, null, OPERATOR));
        planService.start(plan.id());

        advanceService.advanceOnce();

        UpgradePlanInfo after = planService.get(plan.id());
        assertEquals("BRAKED", after.status(), "非令牌寻址的台账行=无消费方，诚实刹车禁白烧");
        assertTrue(commands().isEmpty(), "刹车先于派发，禁半截派发");
        jdbcTemplate.update("DELETE FROM tp_cluster_component WHERE node_id = 'manual-node-n1'");
    }

    // ---------- direction single source (pure, no DB) ----------

    @Test
    void isRollbackDirection_singleSource_parsesStrategyFlag_defensively() {
        assertTrue(WavePlanGenerator.isRollbackDirection("{\"direction\":\"rollback\"}"));
        assertFalse(WavePlanGenerator.isRollbackDirection("{\"direction\":\"forward\"}"));
        assertFalse(WavePlanGenerator.isRollbackDirection(null));
        assertFalse(WavePlanGenerator.isRollbackDirection("{unparseable"),
                "不可解析快照=正向（禁凭空造方向）");
    }
}
