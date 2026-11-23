package com.sixbean.testpike.core.cluster.domain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cluster.api.dto.ClusterCommandInput;
import com.sixbean.testpike.core.cluster.api.dto.ClusterCommandView;
import com.sixbean.testpike.core.cluster.api.dto.EnrollAcceptedView;
import com.sixbean.testpike.core.cluster.api.dto.EnrollTokenIssueInput;
import com.sixbean.testpike.core.cluster.api.dto.EnrollTokenIssuedView;
import com.sixbean.testpike.core.executor.domain.service.ExecutorService;
import com.sixbean.testpike.core.support.ClusterCommandFixtureLedger;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Audit-trail segment of the dedicated cluster command queue (03 protocol §2.9).
 * <p>Full-audit contract for the bidirectional channel: every lifecycle event
 * (dispatch = enqueue, receipt = claim, outcome = complete) must land one
 * tp_audit_log row that is self-attributing — the audit row alone must show
 * which command category it belongs to and, for the receipt leg, the honest
 * outcome of the agent run. Audit rows join the live table only by id, so
 * without snapshots the trail can neither answer "which category" nor
 * "success or failure" once the command row has moved on.
 * <p>Category families hit here: engine-upgrade/install (executor-target form)
 * and agent-upgrade (enroll-token target form). The start-stop / self-inspect
 * families are not yet in the command vocabulary (tracked as the next segment).
 * <p>tp_audit_log is append-only by trigger design: rows stay, assertions
 * filter by this suite's own command ids. Real dev database, real aspect
 * wiring — the audit aspect is never mocked.
 */
@SpringBootTest(classes = CoreApplication.class)
class ClusterCommandAuditTrailTest {

    private static final String OBJECT_TYPE = "cluster_command";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private ClusterCommandService commandService;
    @Autowired
    private ClusterEnrollTokenService enrollTokenService;
    @Autowired
    private ExecutorService executorService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ClusterCommandFixtureLedger fixtureLedger;
    private final List<Long> trackedExecutors = new java.util.concurrent.CopyOnWriteArrayList<>();

    @BeforeEach
    void markWatermarks() {
        fixtureLedger = new ClusterCommandFixtureLedger(jdbcTemplate, "auditcmd-");
        fixtureLedger.markWatermarks();
    }

    @AfterEach
    void sweepRunRows() {
        fixtureLedger.sweepRunRows(trackedExecutors);
    }

    private long registerExecutor(String name) {
        return com.sixbean.testpike.core.support.ExecutorFixtureRegistration
                .register(executorService, name, trackedExecutors);
    }

    private ClusterCommandView enqueue(String type, String targetType, long targetId,
                                       Map<String, Object> payload) {
        return commandService.enqueue(new ClusterCommandInput(
                type, targetType, targetId, payload, "audit-trail-suite"));
    }

    /** Audit rows for one command id, in insertion order. */
    private List<Map<String, Object>> auditRows(long commandId, String action) {
        return jdbcTemplate.queryForList(
                "SELECT id, operator_id FROM tp_audit_log"
                        + " WHERE object_type = ? AND object_id = ? AND action = ?"
                        + " ORDER BY id",
                OBJECT_TYPE, String.valueOf(commandId), action);
    }

    /** Parse one snapshot column of an audit row (jsonb arrives as text; driver Map binding is unsupported). */
    @SuppressWarnings("unchecked")
    private Map<String, Object> snapshot(long auditRowId, String column) {
        String raw = jdbcTemplate.queryForObject(
                "SELECT " + column + "::text FROM tp_audit_log WHERE id = ?", String.class, auditRowId);
        try {
            return raw == null ? null : JSON.readValue(raw, Map.class);
        } catch (Exception e) {
            throw new IllegalStateException("审计快照列解析失败 " + column + "=" + raw, e);
        }
    }

    /**
     * Dispatch leg: the enqueue audit row must carry the command category in
     * after_json so each family (engine-* vs agent-*) is attributable from the
     * audit trail alone.
     */
    @Test
    void enqueueAudit_engineUpgrade_carriesCategoryAttribution() {
        long exId = registerExecutor("auditcmd-engine-ex");
        ClusterCommandView cmd = enqueue("engine-upgrade", "executor", exId,
                Map.of("engines", List.of("MOBILEWRIGHT")));

        List<Map<String, Object>> rows = auditRows(cmd.id(), "enqueue");
        assertEquals(1, rows.size(), "下发必须落且仅落一枚 enqueue 审计行");
        Map<String, Object> after = snapshot(((Number) rows.get(0).get("id")).longValue(), "after_json");
        assertNotNull(after, "enqueue 审计行必须带执行后快照（逐类归属的承载面）");
        assertEquals("engine-upgrade", String.valueOf(after.get("commandType")),
                "审计行必须点名命令类，禁只留 objectId 让账回连已漂移的活行");
        assertEquals("executor", String.valueOf(after.get("targetType")));
        assertEquals("audit-trail-suite", String.valueOf(after.get("operator")),
                "下发操作人必须随快照留账（双向全审计的发起侧）");
    }

    /**
     * Claim leg: a machine taking the command is a protocol-plane write and
     * must leave its own audit row (previously the claim was silent — the
     * trail could not tell dispatch from consumption).
     */
    @Test
    void claimAudit_executorPoll_writesAttributedRow() {
        long exId = registerExecutor("auditcmd-claim-ex");
        ClusterCommandView cmd = enqueue("engine-install", "executor", exId,
                Map.of("engines", List.of("MOBILEWRIGHT")));

        assertTrue(auditRows(cmd.id(), "claim").isEmpty(), "未领取不得有 claim 审计行");
        List<ClusterCommandView> claimed = commandService.pollClaimed(exId, 5);
        assertEquals(1, claimed.size());

        List<Map<String, Object>> rows = auditRows(cmd.id(), "claim");
        assertEquals(1, rows.size(), "领取必须落 claim 审计行");
        long rowId = ((Number) rows.get(0).get("id")).longValue();
        Map<String, Object> after = snapshot(rowId, "after_json");
        assertNotNull(after);
        assertEquals(String.valueOf(exId), String.valueOf(after.get("claimedBy")),
                "领取行必须点名领取者");
        Map<String, Object> before = snapshot(rowId, "before_json");
        assertNotNull(before, "领取行必须带命令类归属快照");
        assertEquals("engine-install", String.valueOf(before.get("commandType")));
    }

    /**
     * Receipt leg, success: the complete audit row must show category
     * (before) and honest outcome (after) without rejoining the live row.
     */
    @Test
    void receiptAudit_successOutcome_landsCategoryAndOutcome() {
        long exId = registerExecutor("auditcmd-ok-ex");
        ClusterCommandView cmd = enqueue("engine-upgrade", "executor", exId,
                Map.of("engines", List.of("MOBILEWRIGHT")));
        commandService.pollClaimed(exId, 5);

        commandService.complete(cmd.id(), exId, true, "引擎升级完成");

        List<Map<String, Object>> rows = auditRows(cmd.id(), "complete");
        assertEquals(1, rows.size(), "执行回执必须落 complete 审计行");
        long rowId = ((Number) rows.get(0).get("id")).longValue();
        Map<String, Object> before = snapshot(rowId, "before_json");
        assertEquals("engine-upgrade", String.valueOf(before.get("commandType")),
                "回执审计行必须逐类归属");
        assertEquals("1", String.valueOf(before.get("statusBefore")),
                "回执前状态入账（已领取）");
        Map<String, Object> after = snapshot(rowId, "after_json");
        assertEquals(Boolean.TRUE, after.get("success"), "成功回执账上必须显真");
        assertEquals("引擎升级完成", String.valueOf(after.get("detail")));
    }

    /** Receipt leg, failure: the trail must say so plainly (failure != silence). */
    @Test
    void receiptAudit_failedOutcome_recordsFailure() {
        long exId = registerExecutor("auditcmd-fail-ex");
        ClusterCommandView cmd = enqueue("engine-uninstall", "executor", exId,
                Map.of("engines", List.of("MOBILEWRIGHT")));
        commandService.pollClaimed(exId, 5);

        commandService.complete(cmd.id(), exId, false, "健康闸未过");

        List<Map<String, Object>> rows = auditRows(cmd.id(), "complete");
        assertEquals(1, rows.size());
        Map<String, Object> after = snapshot(
                ((Number) rows.get(0).get("id")).longValue(), "after_json");
        assertEquals(Boolean.FALSE, after.get("success"), "失败回执账上必须显伪");
        assertEquals("健康闸未过", String.valueOf(after.get("detail")));
    }

    /**
     * Idempotent replay: the command row keeps one terminal state, but the
     * retried receipt attempt stays auditable (agent retry channels exist and
     * a silent replay would erase a real operational event).
     */
    @Test
    void receiptAudit_terminalReplay_attemptStaysAuditable() {
        long exId = registerExecutor("auditcmd-replay-ex");
        ClusterCommandView cmd = enqueue("engine-upgrade", "executor", exId,
                Map.of("engines", List.of("MOBILEWRIGHT")));
        commandService.pollClaimed(exId, 5);
        commandService.complete(cmd.id(), exId, true, "首次回执");
        commandService.complete(cmd.id(), exId, false, "重放伪回执");

        List<Map<String, Object>> rows = auditRows(cmd.id(), "complete");
        assertEquals(2, rows.size(), "重放不改活行，但每次回执尝试必须留账");
        Map<String, Object> second = snapshot(
                ((Number) rows.get(1).get("id")).longValue(), "before_json");
        assertEquals("3", String.valueOf(second.get("statusBefore")),
                "重放行入账前态=成功终态（活行未回退的账证）");
        assertEquals(3, commandService.list(exId).stream()
                        .filter(v -> v.id().equals(cmd.id()))
                        .map(ClusterCommandView::status).findFirst().orElse(-1),
                "重放零副作用：活行仍为成功终态");
    }

    /**
     * Category family hit #2 (agent form): host service-agent commands ride
     * the enroll-token target and must attribute from the audit trail too.
     */
    @Test
    void enqueueAudit_agentUpgrade_tokenForm_carriesCategoryAttribution() {
        EnrollTokenIssuedView issued = enrollTokenService.issue(
                new EnrollTokenIssueInput("executor", null, 1, "等待加入机-审计", "admin"));
        EnrollAcceptedView accepted = enrollTokenService.enroll(issued.token(), "fp-audit-A");
        ClusterCommandView cmd = enqueue("agent-upgrade", "enroll-token", accepted.tokenId(),
                Map.of("target_version", "2026.11.22-r1",
                        "artifact_url", "https://mirror.local/agent-2026.11.22-r1.tar.gz",
                        "sha256", "a".repeat(64)));

        List<Map<String, Object>> rows = auditRows(cmd.id(), "enqueue");
        assertEquals(1, rows.size());
        Map<String, Object> after = snapshot(
                ((Number) rows.get(0).get("id")).longValue(), "after_json");
        assertEquals("agent-upgrade", String.valueOf(after.get("commandType")),
                "agent 类命令的账面归属与 engine 族同标准");
        assertEquals("enroll-token", String.valueOf(after.get("targetType")));
    }

    /**
     * Category family hit #3 (node operations forms, T294b pack B): start-stop
     * and self-check ride the same enroll-token channel and must attribute
     * from the audit trail alone the moment their types join the domain.
     */
    @Test
    void enqueueAudit_nodeControlAndSelfCheck_carriesCategoryAttribution() {
        EnrollTokenIssuedView issued = enrollTokenService.issue(
                new EnrollTokenIssueInput("executor", null, 1, "等待加入机-审计N", "admin"));
        EnrollAcceptedView accepted = enrollTokenService.enroll(issued.token(), "fp-audit-N1");

        ClusterCommandView control = enqueue("platform-service-control", "enroll-token",
                accepted.tokenId(), Map.of("service", "core", "action", "restart"));
        Map<String, Object> controlAfter = snapshot(
                ((Number) auditRows(control.id(), "enqueue").get(0).get("id")).longValue(),
                "after_json");
        assertEquals("platform-service-control", String.valueOf(controlAfter.get("commandType")),
                "启停命令必须与升级族同标准落审计归属");

        ClusterCommandView selfCheck = enqueue("node-self-check", "enroll-token",
                accepted.tokenId(), Map.of("service", "core"));
        Map<String, Object> checkAfter = snapshot(
                ((Number) auditRows(selfCheck.id(), "enqueue").get(0).get("id")).longValue(),
                "after_json");
        assertEquals("node-self-check", String.valueOf(checkAfter.get("commandType")),
                "自检命令必须与升级族同标准落审计归属");
    }
}
