package com.sixbean.testpike.core.cluster.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cluster.api.dto.ClusterCommandInput;
import com.sixbean.testpike.core.cluster.api.dto.ClusterCommandView;
import com.sixbean.testpike.core.cluster.api.dto.EnrollAcceptedView;
import com.sixbean.testpike.core.cluster.api.dto.EnrollHeartbeatView;
import com.sixbean.testpike.core.cluster.api.dto.EnrollTokenIssueInput;
import com.sixbean.testpike.core.cluster.api.dto.EnrollTokenIssuedView;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import com.sixbean.testpike.core.executor.domain.service.ExecTaskService;
import com.sixbean.testpike.core.executor.domain.service.ExecutorService;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
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


@SpringBootTest(classes = CoreApplication.class)
class ClusterCommandServiceTest {

    private static final String CMD_TTL_KEY = "installkit.command-ttl-seconds";

    @Autowired
    private ClusterCommandService commandService;
    @Autowired
    private ClusterEnrollTokenService enrollTokenService;
    @Autowired
    private ExecutorService executorService;
    @Autowired
    private ExecTaskService execTaskService;
    @Autowired
    private ConfigCacheHolder configCacheHolder;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;

    // Fixture-scoped teardown : id watermarks captured before
    // each test; @AfterEach deletes only rows this run created above the
    // floors. The blanket TRUNCATE rewound shared sequences and wiped
    // executors and tasks owned by concurrent suites on the dev database.
    private long commandFloor;
    private long tokenFloor;
    private final java.util.List<Long> trackedExecutors =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    @BeforeEach
    void markWatermarks() {
        commandFloor = SharedDbFixtureSweep.maxId(jdbcTemplate, "tp_cluster_command");
        tokenFloor = SharedDbFixtureSweep.maxId(jdbcTemplate, "tp_cluster_enroll_token");
        // Registration is a natural-key upsert: a crashed run's leftover
        // executor (possibly still flagged drain_requested) would otherwise be
        // reused instead of a fresh row. The cmd- name family belongs to this
        // suite only, so dropping stale members never touches other owners.
        jdbcTemplate.update("DELETE FROM tp_executor WHERE name LIKE 'cmd-%'");
    }

    @AfterEach
    void sweepRunRows() {
        SharedDbFixtureSweep.deleteSince(jdbcTemplate, "tp_cluster_command", commandFloor);
        SharedDbFixtureSweep.deleteSince(jdbcTemplate, "tp_cluster_enroll_token", tokenFloor);
        SharedDbFixtureSweep.byIds(jdbcTemplate, "tp_executor", trackedExecutors);
        trackedExecutors.clear();
        if (projectId != 0L) {
            SharedDbFixtureSweep.byProject(jdbcTemplate, projectId);
            projectId = 0L;
        }
        configCacheHolder.remove(CMD_TTL_KEY);
    }

    private long project() {
        if (projectId == 0L) {
            String code = "cmd" + System.nanoTime();
            jdbcTemplate.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                    "命令队列测试项目-" + code, code);
            projectId = jdbcTemplate.queryForObject("SELECT id FROM tp_project WHERE code = ?",
                    Long.class, code);
        }
        return projectId;
    }

    private long registerExecutor(String name) {
        return com.sixbean.testpike.core.support.ExecutorFixtureRegistration
                .register(executorService, name, trackedExecutors);
    }

    private long insertPendingAppTask() {
        return jdbcTemplate.queryForObject(
                "INSERT INTO tp_exec_task (project_id, case_id, status, schema_version,"
                        + " action_json, task_type) VALUES (?, 0, 1, 'v2', ?, 6) RETURNING id",
                Long.class, project(),
                "{\"kind\":\"app-auto-run\",\"runId\":1,\"engine\":"
                        + "{\"code\":\"MOBILEWRIGHT\",\"platform\":\"ANDROID\",\"deviceKind\":\"EMULATOR\"},"
                        + "\"steps\":[{\"action\":\"app.launch\",\"target\":{\"pkg\":\"com.demo\"}}]}");
    }

    private ClusterCommandView enqueue(String type, String targetType, long targetId,
                                       Map<String, Object> payload) {
        return commandService.enqueue(new ClusterCommandInput(type, targetType, targetId,
                payload, "admin"));
    }


    @Test
    void enqueue_rejectsUnknownCommandType_engineCodeAndTargetType() {
        ServiceException e1 = assertThrows(ServiceException.class,
                () -> enqueue("engine-reboot", "executor", 1L, Map.of()));
        assertTrue(e1.getMessage().contains("engine-install"), "命令型人话点名四值域：" + e1.getMessage());

        long exId = registerExecutor("cmd-gate-ex");
        ServiceException e2 = assertThrows(ServiceException.class,
                () -> enqueue("scale-in", "enroll-token", exId, Map.of()));
        assertTrue(e2.getMessage().contains("执行器"), "缩容目标型必 executor：" + e2.getMessage());

        ServiceException e3 = assertThrows(ServiceException.class, () -> enqueue(
                "engine-install", "executor", exId, Map.of("engines", List.of("NOTAENGINE"))));
        assertTrue(e3.getMessage().contains("NOTAENGINE"), "未收录引擎 code 点名人话拒：" + e3.getMessage());

        ServiceException e4 = assertThrows(ServiceException.class, () -> enqueue(
                "engine-install", "executor", exId, Map.of()));
        assertTrue(e4.getMessage().contains("engines"), "engine 型缺 engines 清单=拒（消费侧无活可干禁白烧）");
    }

    @Test
    void engineUpgradeAndRollback_acceptedByGate_rollbackRequiresTargetVersion() {
        long exId = registerExecutor("cmd-upg-ex");
        ClusterCommandView up = enqueue("engine-upgrade", "executor", exId,
                Map.of("engines", List.of("MOBILEWRIGHT")));
        assertEquals(0, up.status(), "升级命令按 §2.9 扩型入队（重装到钉版语义）");

        ServiceException e = assertThrows(ServiceException.class, () -> enqueue(
                "engine-rollback", "executor", exId, Map.of("engines", List.of("MOBILEWRIGHT"))));
        assertTrue(e.getMessage().contains("target_version"),
                "回滚缺反向钉目标=入队前人话拒（禁假回滚到猜的版本）：" + e.getMessage());

        ClusterCommandView rb = enqueue("engine-rollback", "executor", exId,
                Map.of("engines", List.of("MOBILEWRIGHT"), "target_version", "0.0.62"));
        assertEquals(0, rb.status(), "带目标版本钉的回滚命令可入队");
    }


    @Test
    void enqueue_ttlFromK8Key_missingFallsBackOneDay() {
        configCacheHolder.put(CMD_TTL_KEY, "3600");
        ClusterCommandView keyed = enqueue("engine-install", "executor",
                registerExecutor("cmd-ttl-ex"), Map.of("engines", List.of("MOBILEWRIGHT")));
        long window = secondsBetween(keyed.id());
        assertTrue(window > 3500 && window <= 3600, "TTL 应现读键 3600，实得=" + window);

        configCacheHolder.remove(CMD_TTL_KEY);
        ClusterCommandView fallback = enqueue("engine-uninstall", "executor",
                registerExecutor("cmd-ttl-ex2"), Map.of("engines", List.of("MINIUM")));
        long fbWindow = secondsBetween(fallback.id());
        assertTrue(fbWindow > 85000 && fbWindow <= 86400, "缺键=保守默认 86400，实得=" + fbWindow);
    }

    private long secondsBetween(Long commandId) {
        Number secs = jdbcTemplate.queryForObject(
                "SELECT EXTRACT(EPOCH FROM (expires_at - created_at)) FROM tp_cluster_command WHERE id = ?",
                Number.class, commandId);
        return secs == null ? -1 : secs.longValue();
    }


    @Test
    void pollClaimed_casOnceAndSkipsExpired() {
        long exId = registerExecutor("cmd-poll-ex");
        ClusterCommandView issued = enqueue("engine-install", "executor", exId,
                Map.of("engines", List.of("MOBILEWRIGHT")));

        jdbcTemplate.update("UPDATE tp_cluster_command SET expires_at = now() - interval '1 hour' WHERE id = ?",
                issued.id());
        assertTrue(commandService.pollClaimed(exId, 5).isEmpty(), "过期未领取=poll 过滤拒领");
        jdbcTemplate.update("UPDATE tp_cluster_command SET expires_at = now() + interval '1 hour' WHERE id = ?",
                issued.id());

        List<ClusterCommandView> claimed = commandService.pollClaimed(exId, 5);
        assertEquals(1, claimed.size());
        assertEquals(issued.id(), claimed.get(0).id());
        assertEquals(1, claimed.get(0).status(), "领取后=已领取");
        assertEquals(exId, claimed.get(0).claimedBy(), "claimed_by=领取执行器");
        assertTrue(commandService.pollClaimed(exId, 5).isEmpty(), "已领取命令不再重复可得");

        long other = registerExecutor("cmd-poll-other");
        ClusterCommandView second = enqueue("engine-uninstall", "executor", exId,
                Map.of("engines", List.of("MINIUM")));
        assertTrue(commandService.pollClaimed(other, 5).isEmpty(), "executor 型按 target 定向，异器不可得");
        assertEquals(0, commandService.list(other).size(), "list 过滤=该器名下命令");
        assertEquals(2, commandService.list(exId).size());
    }


    @Test
    void progressAndComplete_lifecycle_guardedByClaimer_idempotentTerminal_revokeOnlyPending() {
        long exId = registerExecutor("cmd-life-ex");
        long other = registerExecutor("cmd-life-other");
        ClusterCommandView issued = enqueue("engine-install", "executor", exId,
                Map.of("engines", List.of("MOBILEWRIGHT")));

        assertThrows(ServiceException.class,
                () -> commandService.progress(issued.id(), exId, "install", "开始装"));

        commandService.pollClaimed(exId, 5);
        ServiceException wrong = assertThrows(ServiceException.class,
                () -> commandService.progress(issued.id(), other, "install", "开始装"));
        assertTrue(wrong.getMessage().contains("领取"), "非领取者推进人话拒：" + wrong.getMessage());

        commandService.progress(issued.id(), exId, "install", "npm install 进行中");
        assertEquals(2, statusOf(issued.id()), "推进=执行中");
        Map<String, Object> progress = progressOf(issued.id());
        assertEquals("install", progress.get("step"));

        assertThrows(ServiceException.class,
                () -> commandService.complete(issued.id(), other, true, "伪回执"));
        commandService.complete(issued.id(), exId, true, "装好了");
        assertEquals(3, statusOf(issued.id()), "成功回执=终态成功");
        assertEquals("装好了", jdbcTemplate.queryForObject(
                "SELECT result_detail FROM tp_cluster_command WHERE id = ?",
                String.class, issued.id()));

        commandService.complete(issued.id(), exId, false, "迟到的失败回执");
        assertEquals(3, statusOf(issued.id()), "终态不可翻覆");

        ClusterCommandView pending = enqueue("engine-uninstall", "executor", exId,
                Map.of("engines", List.of("MINIUM")));
        commandService.revokePending(pending.id());
        assertEquals(5, statusOf(pending.id()), "撤销=5 已撤销");
        assertThrows(ServiceException.class, () -> commandService.revokePending(pending.id()),
                "已撤销再撤=人话拒");
        ClusterCommandView claimedAgain = enqueue("engine-install", "executor", exId,
                Map.of("engines", List.of("MOBILEWRIGHT")));
        commandService.pollClaimed(exId, 5);
        assertThrows(ServiceException.class, () -> commandService.revokePending(claimedAgain.id()),
                "已领取不可撤（执行侧可能已开烧）");
    }

    private int statusOf(long commandId) {
        Integer status = jdbcTemplate.queryForObject(
                "SELECT status FROM tp_cluster_command WHERE id = ?", Integer.class, commandId);
        return status == null ? -1 : status;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> progressOf(long commandId) {
        String json = jdbcTemplate.queryForObject(
                "SELECT last_progress::text FROM tp_cluster_command WHERE id = ?",
                String.class, commandId);
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }


    @Test
    void scaleIn_drainGate_stopsClaim_untilSuccessReceiptReleases() {
        long exId = registerExecutor("cmd-drain-ex");
        long taskId = insertPendingAppTask();

        assertNotNull(execTaskService.claim(exId), "基线：正常执行器可领");

        ClusterCommandView scaleIn = enqueue("scale-in", "executor", exId, Map.of());
        assertEquals(Boolean.TRUE, jdbcTemplate.queryForObject(
                "SELECT drain_requested FROM tp_executor WHERE id = ?", Boolean.class, exId),
                "scale-in 入队同事务置排空旗（S3：停 claim）");
        assertEquals(2, jdbcTemplate.queryForObject(
                "SELECT status FROM tp_exec_task WHERE id = ?", Integer.class, taskId),
                "已领取任务不受影响（在跑归零后退）");

        long nextTask = insertPendingAppTask();
        assertNull(execTaskService.claim(exId), "排空期停派发新任务（claim 返回 null 非异常）");
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT status FROM tp_exec_task WHERE id = ?", Integer.class, nextTask),
                "排空期新任务滞留待派发（可改派他器）");

        commandService.pollClaimed(exId, 5);
        commandService.progress(scaleIn.id(), exId, "drain", "在跑归零，退池收尾");
        commandService.complete(scaleIn.id(), exId, true, "已排空退池");
        assertEquals(Boolean.FALSE, jdbcTemplate.queryForObject(
                "SELECT drain_requested FROM tp_executor WHERE id = ?", Boolean.class, exId),
                "成功回执=释放排空旗");
        assertNotNull(execTaskService.claim(exId), "旗释放后恢复派发");
    }


    @Test
    void enrollTokenTarget_matchedFingerprintClaims_othersRejected() {
        EnrollTokenIssuedView issued = enrollTokenService.issue(
                new EnrollTokenIssueInput("executor", null, 1, "等待加入机", "admin"));
        EnrollAcceptedView accepted = enrollTokenService.enroll(issued.token(), "fp-machine-A");
        ClusterCommandView cmd = enqueue("engine-install", "enroll-token", accepted.tokenId(),
                Map.of("engines", List.of("MOBILEWRIGHT")));

        assertTrue(commandService.pollClaimedForEnroll(accepted.tokenId(), "fp-machine-X", 5).isEmpty(),
                "绑定指纹≠来者指纹=不可得");
        List<ClusterCommandView> got = commandService.pollClaimedForEnroll(
                accepted.tokenId(), "fp-machine-A", 5);
        assertEquals(1, got.size());
        assertEquals(cmd.id(), got.get(0).id());
        assertEquals(1, got.get(0).status());

        assertThrows(ServiceException.class,
                () -> enqueue("node-remove", "enroll-token", accepted.tokenId(), Map.of()),
                "node-remove/scale-in 必 executor 目标（令牌无执行器行可 drain）");
    }


    @Test
    void heartbeat_stepKey_projectsLastStep_legacyCallUnchanged() {
        EnrollTokenIssuedView issued = enrollTokenService.issue(
                new EnrollTokenIssueInput("ai-node", null, 1, null, "admin"));

        EnrollHeartbeatView legacy = enrollTokenService.heartbeat(
                issued.token(), "fp-m", null, null, null);
        assertEquals(Boolean.TRUE, legacy.accepted());
        assertEquals(0, legacy.status(), "未核销令牌态=待用");
        assertNull(legacy.lastStep(), "不带 step=零残留投影");

        EnrollHeartbeatView stepped = enrollTokenService.heartbeat(
                issued.token(), "fp-m", "deps", "running", "装 node 运行时");
        assertNotNull(stepped.lastStep());
        assertEquals("deps", stepped.lastStep().get("step"));
        assertEquals("running", stepped.lastStep().get("stepStatus"));
        assertEquals("装 node 运行时", stepped.lastStep().get("message"));

        enrollTokenService.heartbeat(issued.token(), "fp-m", "healthcheck", "done", "体检通过");
        String json = jdbcTemplate.queryForObject(
                "SELECT last_step::text FROM tp_cluster_enroll_token WHERE id = ?",
                String.class, issued.id());
        assertTrue(json.contains("healthcheck") && !json.contains("deps"),
                "last_step=最近一步覆盖写，实得=" + json);

        ServiceException e = assertThrows(ServiceException.class, () -> enrollTokenService.heartbeat(
                issued.token(), "fp-m", "reboot", "done", null));
        assertTrue(e.getMessage().contains("download"), "非法步骤键点名四值域：" + e.getMessage());
        assertThrows(ServiceException.class, () -> enrollTokenService.heartbeat(
                issued.token(), "fp-m", "deps", "paused", null));

        enrollTokenService.enroll(issued.token(), "fp-machine-A");
        assertThrows(ServiceException.class, () -> enrollTokenService.heartbeat(
                issued.token(), "fp-machine-X", "deps", "done", null));
    }

    // ── ⑩ : four platform/agent command types join the queue (design
    // §8A.3: target=host, consumed by the installkit service-agent over the
    // enroll-token channel). The enqueue gate is fail-closed — the agent must
    // never receive a command it would have to guess at (missing service code
    // / pinned version / artifact digest / sha256 = rejected before queuing) ──

    private static Map<String, Object> payloadWith(Map<String, Object> overrides) {
        Map<String, Object> base = new java.util.HashMap<>();
        base.put("service", "admin");
        base.put("target_version", "1.9.0");
        base.put("artifact_url", "https://mirror.example/releases/1.9.0/admin.jar");
        base.put("sha256", "a".repeat(64));
        base.putAll(overrides);
        return Map.copyOf(base);
    }

    /** Enrolled platform host = bound enroll-token row (agent polls by token+fingerprint). */
    private long hostToken() {
        EnrollTokenIssuedView issued = enrollTokenService.issue(
                new EnrollTokenIssueInput("platform", null, 1, "平台主机-cmd", "admin"));
        return enrollTokenService.enroll(issued.token(), "fp-host-A").tokenId();
    }

    @Test
    void platformServiceCommands_acceptedOnHostTarget_failClosedOnMissingFields() {
        long hostId = hostToken();
        ClusterCommandView up = enqueue("platform-service-upgrade", "enroll-token", hostId,
                payloadWith(Map.of()));
        assertEquals(0, up.status(), "platform-service-upgrade with full pin queues on host target");

        // agent has no executor row — an executor-target platform command would burn
        // forever in the queue nobody consumes (runner fail-closed keeps rejecting)
        assertThrows(ServiceException.class, () -> enqueue(
                "platform-service-upgrade", "executor", registerExecutor("cmd-platform-ex"),
                payloadWith(Map.of())), "platform command must target the host token channel");

        ServiceException noService = assertThrows(ServiceException.class, () -> enqueue(
                "platform-service-upgrade", "enroll-token", hostId,
                payloadWith(Map.of("service", "mysqld"))));
        assertTrue(noService.getMessage().contains("admin"),
                "unknown service code rejected against the platform family single source: "
                        + noService.getMessage());

        ServiceException noVersion = assertThrows(ServiceException.class, () -> enqueue(
                "platform-service-upgrade", "enroll-token", hostId,
                payloadWith(Map.of("target_version", ""))));
        assertTrue(noVersion.getMessage().contains("target_version"),
                "missing pinned version = human-worded reject: " + noVersion.getMessage());

        ServiceException noDigest = assertThrows(ServiceException.class, () -> enqueue(
                "platform-service-upgrade", "enroll-token", hostId,
                payloadWith(Map.of("sha256", "deadbeef"))));
        assertTrue(noDigest.getMessage().contains("sha256"),
                "non-64-hex digest rejected (agent never swaps an unverifiable package): "
                        + noDigest.getMessage());

        ServiceException rollbackNoPin = assertThrows(ServiceException.class, () -> enqueue(
                "platform-service-rollback", "enroll-token", hostId,
                payloadWith(Map.of("target_version", ""))));
        assertTrue(rollbackNoPin.getMessage().contains("target_version"),
                "rollback keeps the reverse-pin discipline of engine-rollback: "
                        + rollbackNoPin.getMessage());

        ClusterCommandView rb = enqueue("platform-service-rollback", "enroll-token", hostId,
                payloadWith(Map.of()));
        assertEquals(0, rb.status(), "full-pin rollback queues fine");
    }

    @Test
    void agentCommands_acceptedWithArtifactPin_rejectMissingDigestOrUrl() {
        long hostId = hostToken();
        // agent-upgrade carries no service code — it replaces the agent package itself
        Map<String, Object> payload = Map.of(
                "target_version", "1.2.0",
                "artifact_url", "https://mirror.example/releases/1.2.0/service-agent.tar.gz",
                "sha256", "b".repeat(64));
        ClusterCommandView up = enqueue("agent-upgrade", "enroll-token", hostId, payload);
        assertEquals(0, up.status(), "agent-upgrade queues with full artifact pin");

        ServiceException noUrl = assertThrows(ServiceException.class, () -> enqueue(
                "agent-upgrade", "enroll-token", hostId,
                Map.of("target_version", "1.2.0", "sha256", "b".repeat(64))));
        assertTrue(noUrl.getMessage().contains("artifact_url"),
                "missing artifact_url rejected — nothing to fetch: " + noUrl.getMessage());

        ServiceException noSha = assertThrows(ServiceException.class, () -> enqueue(
                "agent-rollback", "enroll-token", hostId,
                Map.of("target_version", "1.1.0",
                        "artifact_url", "https://mirror.example/releases/1.1.0/service-agent.tar.gz")));
        assertTrue(noSha.getMessage().contains("sha256"),
                "agent-rollback without digest rejected (bad package never reaches the host): "
                        + noSha.getMessage());

        ClusterCommandView rb = enqueue("agent-rollback", "enroll-token", hostId, Map.of(
                "target_version", "1.1.0",
                "artifact_url", "https://mirror.example/releases/1.1.0/service-agent.tar.gz",
                "sha256", "c".repeat(64)));
        assertEquals(0, rb.status(), "full-pin agent rollback queues fine");
    }

    // T294b pack B: the two host-operation types gate on their own value
    // domains (managed family code + action verb), never on artifact fields,
    // and only over the enroll-token agent channel.
    @Test
    void hostOperationCommands_gateActionAndServiceDomains_beforeEnqueue() {
        EnrollTokenIssuedView issued = enrollTokenService.issue(
                new EnrollTokenIssueInput("executor", null, 1, "等待加入机-运维闸", "admin"));
        EnrollAcceptedView accepted = enrollTokenService.enroll(issued.token(), "fp-machine-B2");
        long hostId = accepted.tokenId();

        ClusterCommandView ok = enqueue("platform-service-control", "enroll-token", hostId,
                Map.of("service", "core", "action", "restart"));
        assertEquals(0, ok.status(),
                "a start-stop command carries no artifact — the upgrade gate must not demand one");

        ServiceException badAction = assertThrows(ServiceException.class, () -> enqueue(
                "platform-service-control", "enroll-token", hostId,
                Map.of("service", "core", "action", "pause")));
        assertTrue(badAction.getMessage().contains("pause"), "refusal names the offending value");
        assertTrue(badAction.getMessage().contains("start") && badAction.getMessage().contains("stop")
                        && badAction.getMessage().contains("restart"),
                "refusal lists the valid action domain: " + badAction.getMessage());

        ServiceException noAction = assertThrows(ServiceException.class, () -> enqueue(
                "platform-service-control", "enroll-token", hostId, Map.of("service", "core")));
        assertTrue(noAction.getMessage().contains("action"),
                "missing action refused with the domain listed: " + noAction.getMessage());

        ServiceException badService = assertThrows(ServiceException.class, () -> enqueue(
                "node-self-check", "enroll-token", hostId, Map.of("service", "mysqld")));
        assertTrue(badService.getMessage().contains("mysqld") && badService.getMessage().contains("core"),
                "stray service code refused naming the family list: " + badService.getMessage());

        ClusterCommandView fleet = enqueue("node-self-check", "enroll-token", hostId, Map.of());
        assertEquals(0, fleet.status(), "self-check without service = honest fleet-wide check");

        long exId = registerExecutor("cmd-ops-ex");
        ServiceException wrongChannel = assertThrows(ServiceException.class, () -> enqueue(
                "platform-service-control", "executor", exId,
                Map.of("service", "core", "action", "start")));
        assertTrue(wrongChannel.getMessage().contains("enroll-token"),
                "agent-consumed types must never queue against the executor channel");
    }
}
