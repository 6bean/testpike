package com.sixbean.testpike.core.batch.domain.service.impl;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.batch.api.dto.BatchCreateInput;
import com.sixbean.testpike.core.batch.api.dto.BatchInfo;
import com.sixbean.testpike.core.batch.api.dto.BatchStartResult;
import com.sixbean.testpike.core.batch.domain.service.BatchService;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.credential.api.dto.CredentialInput;
import com.sixbean.testpike.core.credential.domain.service.CredentialService;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.api.dto.ProjectEnvInput;
import com.sixbean.testpike.core.project.domain.service.ProjectEnvService;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.script.api.dto.ScriptSaveInput;
import com.sixbean.testpike.core.script.domain.service.ScriptService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import com.sixbean.testpike.core.session.fixture.CaseSessionFixtures;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Login-state start gate wiring (AV3 trigger ①): BatchServiceImpl.start must
 * sweep every approved case's declared sessionState through the single-source
 * SessionPreflightSupport before the batch leaves draft.
 * <p>Contracts nailed here:
 * ① an EXPIRED declared state refuses the whole batch — validation error whose
 * text names the state, the ledger page, the config path plus the key name
 * (R32 two-element guidance) and the protected-page verification target;
 * ② a declared state missing from the ledger is blocked the same way, listing
 * the selector the case asked for;
 * ④ legacy cases without a declaration keep the old start behaviour unchanged
 * (zero drift);
 * ⑤ a mixed batch refuses on the one expired member and never lists the valid
 * one in the refusal;
 * ⑥ an unparseable expiry (UNKNOWN light) only warns: start succeeds and the
 * start result message carries the notice instead of intercepting.
 * <p>Real service against the chain-slot dev DB, fixtures created per project
 * and removed by project scope (factory baseline untouched, no TRUNCATE).
 */
@SpringBootTest(classes = CoreApplication.class)
class BatchLoginStateStartGateTest {

    private static final AtomicLong SEQ = new AtomicLong();

    @Autowired
    private BatchService batchService;
    @Autowired
    private CaseService caseService;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private ProjectEnvService projectEnvService;
    @Autowired
    private ScriptService scriptService;
    @Autowired
    private CredentialService credentialService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;
    private long envId;

    private CaseSessionFixtures fixtures;

    @BeforeEach
    void seedTopology() {
        long tag = SEQ.incrementAndGet();
        String code = "LG" + System.currentTimeMillis() % 100000000L + tag;
        projectId = projectService.create(1L, new ProjectCreateInput(
                "登录态闸门项目" + code, code, "zh-CN", "P1", null));
        envId = projectEnvService.create(projectId, new ProjectEnvInput(
                "登录态闸门环境", 2, "http://127.0.0.1:8099", null, "web", "chromium", "v1.0.0"));
        fixtures = new CaseSessionFixtures(caseService, scriptService, jdbcTemplate);
    }

    @AfterEach
    void removeFixtures() {

        fixtures.purgeProjectFixtures(projectId);
    }

    @Test
    void rejectsStartWhenDeclaredStateExpired() {
        long caseId = approvedCase("过期账号用例", "gate-expired-" + System.nanoTime());
        withSessionScript(caseId, "闸门过期账号");
        seedState("闸门过期账号", "now() - interval '2 hours'");
        BatchInfo batch = draftBatch("过期拒起批", List.of(caseId));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> batchService.start(1L, batch.id()));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        String msg = ex.getMessage();
        assertTrue(msg.contains("闸门过期账号"), "点名失效登录态：" + msg);
        assertTrue(msg.contains("过期账号用例"), "点名被卡用例：" + msg);
        assertTrue(msg.contains("登录态账本"), "指路账本页（重新采集/续期入口）：" + msg);
        assertTrue(msg.contains("session.renew-lead-minutes"), "点名提前量参数：" + msg);
        assertTrue(msg.contains("受保护页面"), "D11 有效性判断对象说明：" + msg);
        assertEquals(1, batchService.get(batch.id()).status(), "拒起后批次仍为草稿");
        assertEquals(0, taskCount(batch.id()), "拒起不建任务");
    }

    @Test
    void rejectsStartWhenDeclaredStateMissingFromLedger() {
        long caseId = approvedCase("缺账用例", "gate-missing-" + System.nanoTime());
        withSessionScript(caseId, "闸门缺失账号");
        BatchInfo batch = draftBatch("缺账拒起批", List.of(caseId));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> batchService.start(1L, batch.id()));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("闸门缺失账号"),
                "拒单含用例所声明的名称：" + ex.getMessage());
        assertTrue(ex.getMessage().contains("没有可解析的有效条目"), ex.getMessage());
        assertEquals(0, taskCount(batch.id()));
    }

    @Test
    void startsWhenDeclaredStateValid() {
        long caseId = approvedCase("健康账号用例", "gate-valid-" + System.nanoTime());
        withSessionScript(caseId, "闸门健康账号");
        seedState("闸门健康账号", "now() + interval '3 days'");
        BatchInfo batch = draftBatch("健康放行批", List.of(caseId));

        BatchStartResult r = batchService.start(1L, batch.id());
        assertEquals(1, taskCount(batch.id()), "有效登录态照常起批入队");
        assertFalse(r.message().contains("不会启动"), r.message());
    }

    @Test
    void startsUnchangedForLegacyCasesWithoutDeclaration() {
        long caseId = approvedCase("老脚本用例", "gate-legacy-" + System.nanoTime());
        scriptService.save(new ScriptSaveInput(caseId, "v2",
                "{\"caseId\":" + caseId + ",\"steps\":[]}", 2));
        BatchInfo batch = draftBatch("零漂移放行批", List.of(caseId));

        BatchStartResult r = batchService.start(1L, batch.id());
        assertEquals(1, taskCount(batch.id()), "无声明用例行为不变");
        assertFalse(r.message().contains("登录态"), "无声明不带登录态噪音：" + r.message());
    }

    @Test
    void rejectsWholeBatchWhenAnyCaseExpired() {
        long good = approvedCase("健康成员", "gate-mix-good-" + System.nanoTime());
        withSessionScript(good, "闸门混合健康");
        seedState("闸门混合健康", "now() + interval '3 days'");
        long bad = approvedCase("过期成员", "gate-mix-bad-" + System.nanoTime());
        withSessionScript(bad, "闸门混合过期");
        seedState("闸门混合过期", "now() - interval '1 day'");
        BatchInfo batch = draftBatch("混合拒起批", List.of(good, bad));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> batchService.start(1L, batch.id()));
        assertTrue(ex.getMessage().contains("闸门混合过期"), "点名过期成员：" + ex.getMessage());
        assertFalse(ex.getMessage().contains("闸门混合健康"),
                "有效成员不进拒单明细：" + ex.getMessage());
        assertEquals(0, taskCount(batch.id()));
    }

    @Test
    void warnsButAllowsStartForUnknownExpiry() {
        long caseId = approvedCase("有效期未知用例", "gate-unknown-" + System.nanoTime());
        withSessionScript(caseId, "闸门未知账号");
        seedState("闸门未知账号", "NULL");
        BatchInfo batch = draftBatch("未知警示放行批", List.of(caseId));

        BatchStartResult r = batchService.start(1L, batch.id());
        assertEquals(1, taskCount(batch.id()), "未知只警示不拦截");
        assertTrue(r.message().contains("无法识别"), "警示随启动结果可见：" + r.message());
    }

    // ── fixture helpers ────────────────────────────────────────────────────

    private long approvedCase(String title, String caseNo) {
        return fixtures.approvedCase(projectId, title, caseNo);
    }

    private void withSessionScript(long caseId, String stateName) {
        fixtures.sessionScript(caseId, stateName);
    }

    /** Ledger row for this project; the expiry expression is raw SQL (NULL allowed). */
    private void seedState(String stateName, String expirySql) {
        long credentialId = credentialService.create(projectId,
                new CredentialInput("闸门凭据", "gate_cred_" + System.nanoTime(),
                        "gate-secret", null));
        jdbcTemplate.update("INSERT INTO tp_login_state (project_id, state_name, role_scope,"
                        + " account_label, kind, credential_id, scope_origins, status, expires_at)"
                        + " VALUES (?, ?, 'tester', '闸门标签', 'token', ?, '[\"https://lgate.example.com\"]'::jsonb, 1, "
                        + expirySql + ")",
                projectId, stateName, credentialId);
    }

    private BatchInfo draftBatch(String name, List<Long> caseIds) {
        return batchService.create(1L, new BatchCreateInput(projectId, name, envId, caseIds, null));
    }

    private long taskCount(long batchId) {
        Long v = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_exec_task WHERE batch_id = ?", Long.class, batchId);
        return v == null ? 0 : v;
    }
}
