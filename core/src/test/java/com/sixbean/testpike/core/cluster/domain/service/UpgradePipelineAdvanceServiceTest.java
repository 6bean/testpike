package com.sixbean.testpike.core.cluster.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cluster.api.dto.UpgradePlanCreateInput;
import com.sixbean.testpike.core.cluster.api.dto.UpgradePlanInfo;
import com.sixbean.testpike.core.cluster.api.dto.UpgradePlanStepView;
import com.sixbean.testpike.core.cluster.domain.service.impl.UpgradePipelineAdvanceServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class UpgradePipelineAdvanceServiceTest {

    private static final String OP = "t321-adv";

    @Autowired
    private UpgradePipelineAdvanceService advanceService;
    @Autowired
    private UpgradePlanService planService;
    @Autowired
    private ClusterComponentService componentService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long executorId;
    private String node;

    @BeforeEach
    @AfterEach
    void resetFixture() {
        cleanAll();
        executorId = jdbcTemplate.queryForObject(
                "INSERT INTO tp_executor (name) VALUES ('upadv-t321-exec') RETURNING id",
                Long.class);
        node = String.valueOf(executorId);
    }

    private void cleanAll() {
        jdbcTemplate.update("DELETE FROM tp_cluster_component WHERE node_id IN"
                + " (SELECT id::text FROM tp_executor WHERE name = 'upadv-t321-exec')");
        List<Long> planIds = jdbcTemplate.queryForList(
                "SELECT id FROM tp_upgrade_plan WHERE operator = '" + OP + "'", Long.class);
        for (Long id : planIds) {
            for (String event : List.of("UPGRADE_STARTED", "CANARY_FAILED",
                    "BATCH_DONE", "ROLLBACK_DONE")) {
                jdbcTemplate.update("DELETE FROM tp_notification WHERE event_type = ? AND dedup_key LIKE ?",
                        event, event + ":" + id + ":%");
            }
        }
        jdbcTemplate.update("DELETE FROM tp_upgrade_plan WHERE operator = ?", OP);
        jdbcTemplate.update("DELETE FROM tp_upgrade_plan_step WHERE plan_id NOT IN"
                + " (SELECT id FROM tp_upgrade_plan)");
        jdbcTemplate.update("DELETE FROM tp_cluster_command WHERE operator = ?", OP);
        jdbcTemplate.update("DELETE FROM tp_cluster_component WHERE node_id LIKE 'upadv-t321-%'");
        jdbcTemplate.update("DELETE FROM tp_executor WHERE name = 'upadv-t321-exec'");
    }

    
    private long seedRunningPlan(String baselineRef) {
        componentService.upsert(node, "engine-runtime.node", "22.12", "scan", ">=22.12", "24.0");
        UpgradePlanInfo plan = planService.create(new UpgradePlanCreateInput("engine",
                List.of("MOBILEWRIGHT"), List.of(node), null, baselineRef, null,null,null, OP));
        planService.start(plan.id());
        return plan.id();
    }

    private String planStatus(long planId) {
        return planService.get(planId).status();
    }

    private UpgradePlanStepView canaryStep(long planId) {
        List<UpgradePlanStepView> steps = planService.steps(planId);
        UpgradePlanStepView found = null;
        for (UpgradePlanStepView s : steps) {
            if (!"batch".equals(s.phase())) {
                found = s;
            }
        }
        assertNotNull(found, "灰度步缺失");
        return found;
    }

    private int notificationCount(String event, long planId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_notification WHERE event_type = ? AND dedup_key = ?",
                Integer.class, event, event + ":" + planId + ":" + adminUserId());
        return n == null ? 0 : n;
    }

    private long adminUserId() {
        return jdbcTemplate.queryForList("""
                SELECT id FROM tp_user
                 WHERE deleted = 0 AND (role = 4 OR id IN (
                       SELECT ur.user_id FROM tp_user_role ur
                       JOIN tp_role r ON r.id = ur.role_id WHERE r.code = 'admin'))
                 ORDER BY id LIMIT 1""", Long.class).get(0);
    }


    @Test
    void advance_brakesWhenBaselineRefMissing() {
        long planId = seedRunningPlan(null);
        advanceService.advanceOnce();

        assertEquals("BRAKED", planStatus(planId));
        String reason = planService.get(planId).brakeReason();
        assertNotNull(reason);
        assertTrue(reason.contains("回归套件") || reason.contains("金样例"),
                "刹车归因人话=金样例缺位指引，当前=" + reason);
        assertTrue(notificationCount("CANARY_FAILED", planId) >= 1, "CANARY_FAILED 告警必落账");
        Integer dispatched = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_cluster_command WHERE operator = ? AND command_type = 'engine-upgrade'",
                Integer.class, OP);
        assertEquals(0, dispatched, "金样例缺位禁白烧派发");
    }


    @Test
    void advance_dispatchesCanaryCommandWithEnginesPayload() {
        long planId = seedRunningPlan("1");
        advanceService.advanceOnce();

        List<Map<String, Object>> cmds = jdbcTemplate.queryForList(
                "SELECT id, command_type, target_type, target_id, payload::text, status"
                        + " FROM tp_cluster_command WHERE operator = ? AND command_type = 'engine-upgrade'",
                OP);
        assertEquals(1, cmds.size(), "灰度机各一条 engine-upgrade 命令");
        Map<String, Object> cmd = cmds.get(0);
        assertEquals("executor", cmd.get("target_type"));
        assertEquals(executorId, ((Number) cmd.get("target_id")).longValue(),
                "node_id=String.valueOf(executor.id) 同谱寻址");
        assertTrue(String.valueOf(cmd.get("payload")).contains("MOBILEWRIGHT"),
                "engines 载荷=描述符 code，当前=" + cmd.get("payload"));

        UpgradePlanStepView step = canaryStep(planId);
        assertEquals("RUNNING", step.status());
        assertNotNull(step.commandId(), "command_id 回填=唯一下发面引用");
        assertEquals(((Number) cmd.get("id")).longValue(), step.commandId().longValue());
        assertTrue(notificationCount("UPGRADE_STARTED", planId) >= 1, "UPGRADE_STARTED 通知每计划一次");
        assertEquals("RUNNING", planStatus(planId), "命令未回执计划保持在跑");

        advanceService.advanceOnce();
        Integer again = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_cluster_command WHERE operator = ?", Integer.class, OP);
        assertEquals(1, again, "推进器重入幂等——在跑步未回执不重派");
    }


    @Test
    void advance_commandFailureBrakesAndQueuesRollback() {
        long planId = seedRunningPlan("1");
        advanceService.advanceOnce();
        Long upgradeCmdId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_cluster_command WHERE operator = ? AND command_type = 'engine-upgrade'",
                Long.class, OP);
        jdbcTemplate.update("UPDATE tp_cluster_command SET status = 4 WHERE id = ?", upgradeCmdId);

        advanceService.advanceOnce();

        assertEquals("BRAKED", planStatus(planId));
        List<Map<String, Object>> rbk = jdbcTemplate.queryForList(
                "SELECT payload::text FROM tp_cluster_command"
                        + " WHERE operator = ? AND command_type = 'engine-rollback'", OP);
        assertEquals(1, rbk.size(), "灰度机逐台排入回滚");
        assertTrue(String.valueOf(rbk.get(0).get("payload")).contains("22.12"),
                "反向钉 target_version=步 current_version（禁假回滚），当前=" + rbk.get(0));
    }


    @Test
    void advance_rollbackCompletionMarksStepAndNotifies() {
        long planId = seedRunningPlan("1");
        advanceService.advanceOnce();
        Long upgradeCmdId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_cluster_command WHERE operator = ? AND command_type = 'engine-upgrade'",
                Long.class, OP);
        jdbcTemplate.update("UPDATE tp_cluster_command SET status = 4 WHERE id = ?", upgradeCmdId);
        advanceService.advanceOnce();
        Long rbkCmdId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_cluster_command WHERE operator = ? AND command_type = 'engine-rollback'",
                Long.class, OP);

        jdbcTemplate.update("UPDATE tp_cluster_command SET status = 3 WHERE id = ?", rbkCmdId);
        advanceService.advanceOnce();

        assertEquals("rollback", canaryStep(planId).phase(), "回滚完成步转 rollback 位");
        assertTrue(notificationCount("ROLLBACK_DONE", planId) >= 1, "ROLLBACK_DONE 通知落账");
        assertEquals("BRAKED", planStatus(planId), "计划留 BRAKED（回滚不改写刹车终态）");

        advanceService.advanceOnce();
        assertTrue(notificationCount("ROLLBACK_DONE", planId) >= 1);
    }


    @Test
    void cooldownElapsed_pureFunction() {
        LocalDateTime now = LocalDateTime.now();
        assertTrue(UpgradePipelineAdvanceServiceImpl.cooldownElapsed(now, null, 300),
                "无上一波=立即可派");
        assertTrue(UpgradePipelineAdvanceServiceImpl.cooldownElapsed(now, now.minusSeconds(301), 300));
        assertFalse(UpgradePipelineAdvanceServiceImpl.cooldownElapsed(now, now.minusSeconds(1), 300),
                "冷却未足不派下波（键驱动禁 sleep）");
        assertTrue(UpgradePipelineAdvanceServiceImpl.cooldownElapsed(now, now.minusSeconds(1), 0),
                "cool=0 即派（L3 快速链口径）");
    }
}
