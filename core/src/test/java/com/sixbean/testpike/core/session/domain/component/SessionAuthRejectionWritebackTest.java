package com.sixbean.testpike.core.session.domain.component;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.batch.api.dto.BatchCreateInput;
import com.sixbean.testpike.core.batch.api.dto.BatchInfo;
import com.sixbean.testpike.core.batch.domain.service.BatchService;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.credential.api.dto.CredentialInput;
import com.sixbean.testpike.core.credential.domain.service.CredentialService;
import com.sixbean.testpike.core.executor.api.dto.ClaimTaskView;
import com.sixbean.testpike.core.executor.api.dto.ExecResultInput;
import com.sixbean.testpike.core.executor.api.dto.ExecutorRegisterInput;
import com.sixbean.testpike.core.executor.domain.service.ExecTaskService;
import com.sixbean.testpike.core.executor.domain.service.ExecutorService;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.api.dto.ProjectEnvInput;
import com.sixbean.testpike.core.project.domain.service.ProjectEnvService;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.script.api.dto.ScriptSaveInput;
import com.sixbean.testpike.core.script.domain.service.ScriptService;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import com.sixbean.testpike.core.session.fixture.CaseSessionFixtures;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Immediate writeback of an auth rejection observed on a consumed login state
 * (AV face 3, series). When a browser task declares a ledger state and the
 * run's evidence carries an unexpected HTTP 401/403, the result-submission hook
 * must mark that state invalid (status light turns off, remark names the
 * rejection) through the single write source — no second UPDATE path (red line 6).
 * <p>Contracts nailed here:
 * ① valid declared state + http.request snapshot status=401 → row status=0 and
 * the remark names the HTTP code;
 * ② snapshot where the case explicitly asserts expect=403 → judged as intended,
 * zero writeback (negative-case exemption shared with api_runner C1);
 * ③ legacy task without a sessionState declaration → untouched ledger (zero drift);
 * ④ ledger row deleted mid-flight (resolve throws) → submitResult still completes,
 * terminal state persisted (the hook never breaks the relay chain);
 * ⑤ after the writeback the next batch start is refused by the start gate —
 * closed fuse loop (invalid light → preflight refusal);
 * ⑥ netHits evidence with a string-form status (cookie_set state) → same
 * writeback, Number/String tolerance proven.
 * ⑦ sessionAuthRejections navigation plane (34b) inside the ledger origin
 * whitelist → writeback; ⑧ the same plane outside the whitelist → zero side effect.
 * <p>Real chain: project/env/case/script/batch/claim/result all through live
 * services against the chain-slot DB; fixtures cleaned by project scope.
 */
@SpringBootTest(classes = CoreApplication.class)
class SessionAuthRejectionWritebackTest {    /** Suite-exclusive executor seat prefix (fresh-install determinism). */
    private static final String EXEC_PREFIX = "tpf-sess-auth-";
    private com.sixbean.testpike.core.support.FleetSeatFixture fleetSeat;


    private static final AtomicLong SEQ = new AtomicLong();

    @Autowired
    private BatchService batchService;
    @Autowired
    private CaseService caseService;
    @Autowired
    private ProjectService projectService;
    private CaseSessionFixtures fixtures;
    @Autowired
    private ProjectEnvService projectEnvService;
    @Autowired
    private ScriptService scriptService;
    @Autowired
    private CredentialService credentialService;
    @Autowired
    private ExecutorService executorService;
    @Autowired
    private ExecTaskService execTaskService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;
    private long envId;
    /** H4 pool isolation: batches pin this project-owned tag and only this
     * suite's pool-member executors can claim their tasks — a live dev-slot
     * runner polling the same database honestly skips the candidates. */
    private long poolTag;

    @BeforeEach
    void seedTopology() {
        // Fresh-install determinism: this suite routes on the shared fleet
        // registry, so it brings its own engine-capable seat and quiesces the
        // rest of the online view for the window (single-source fixture, R40).
        fleetSeat = com.sixbean.testpike.core.support.FleetSeatFixture.open(jdbcTemplate, EXEC_PREFIX, "GATLING", "K6");

        long tag = SEQ.incrementAndGet();
        String code = "SC" + System.currentTimeMillis() % 100000000L + tag;
        projectId = projectService.create(1L, new ProjectCreateInput(
                "回写验证项目" + code, code, "zh-CN", "P1", null));
        envId = projectEnvService.create(projectId, new ProjectEnvInput(
                "回写验证环境", 2, "http://127.0.0.1:8099", null, "web", "chromium", "v1.0.0"));
        poolTag = jdbcTemplate.queryForObject(
                "INSERT INTO tp_tag_dict (project_id, name, type) VALUES (?, '回写隔离资源池', 7) RETURNING id",
                Long.class, projectId);
        fixtures = new CaseSessionFixtures(caseService, scriptService, jdbcTemplate);
    }

    @AfterEach
    void removeFixtures() {

        fixtures.purgeSnapshots(projectId);
        fixtures.purgeProjectFixtures(projectId);
        SharedDbFixtureSweep.executorsByNamePrefixes(jdbcTemplate, "ex-scs-");
    
        if (fleetSeat != null) {
            fleetSeat.close();
            fleetSeat = null;
        }
}

    @Test
    void marksStateInvalidWhenSnapshotCarriesUnexpectedUnauthorized() {
        long caseId = approvedCase("受保护页面用例", "wb-401-" + System.nanoTime());
        withSessionScript(caseId, "回写健康账号");
        seedState("回写健康账号", "token", "now() + interval '3 days'");
        ClaimTaskView view = claimStartedTask(List.of(caseId), "回写401批");

        submitHttpSnapshot(view, 401, List.of(Map.of("type", "status", "expect", 200)));

        Map<String, Object> row = stateRow("回写健康账号");
        assertEquals(0, ((Number) row.get("st")).intValue(),
                "unexpected 401 must turn the state light off");
        assertNotNull(row.get("rk"), "remark must carry the rejection reason");
        assertTrue(String.valueOf(row.get("rk")).contains("401"),
                "remark names the HTTP code: " + row.get("rk"));
        assertEquals(4, taskStatus(view.taskId()), "task terminal state untouched by the hook");
    }

    @Test
    void keepsStateWhenCaseExplicitlyExpectsForbidden() {
        long caseId = approvedCase("拒绝态断言用例", "wb-neg-" + System.nanoTime());
        withSessionScript(caseId, "回写负向账号");
        seedState("回写负向账号", "token", "now() + interval '3 days'");
        ClaimTaskView view = claimStartedTask(List.of(caseId), "回写负向批");

        submitHttpSnapshot(view, 403, List.of(Map.of("type", "status", "expect", 403)));

        Map<String, Object> row = stateRow("回写负向账号");
        assertEquals(1, ((Number) row.get("st")).intValue(),
                "explicit 403 assertion is the expected outcome — no writeback");
        assertTrue(row.get("rk") == null || String.valueOf(row.get("rk")).isBlank(),
                "remark stays untouched for intended rejections: " + row.get("rk"));
    }

    @Test
    void legacyTaskWithoutDeclarationKeepsLedgerUntouched() {
        long caseId = approvedCase("老脚本用例", "wb-legacy-" + System.nanoTime());
        scriptService.save(new ScriptSaveInput(caseId, "v2",
                "{\"caseId\":" + caseId + ",\"steps\":[]}", 2));
        seedState("回写无关账号", "token", "now() + interval '3 days'");
        ClaimTaskView view = claimStartedTask(List.of(caseId), "零漂移批");

        submitHttpSnapshot(view, 401, List.of(Map.of("type", "status", "expect", 200)));

        Map<String, Object> row = stateRow("回写无关账号");
        assertEquals(1, ((Number) row.get("st")).intValue(),
                "no declaration = no ledger side effect (zero drift)");
    }

    @Test
    void missingLedgerRowDoesNotBreakResultSubmission() {
        long caseId = approvedCase("中途删账用例", "wb-del-" + System.nanoTime());
        withSessionScript(caseId, "回写中途删账");
        seedState("回写中途删账", "token", "now() + interval '3 days'");
        ClaimTaskView view = claimStartedTask(List.of(caseId), "删账不炸批");
        jdbcTemplate.update("DELETE FROM tp_login_state WHERE project_id = ? AND state_name = ?",
                projectId, "回写中途删账");

        assertDoesNotThrow(() -> submitHttpSnapshot(view, 401,
                List.of(Map.of("type", "status", "expect", 200))),
                "unresolvable state at submit time is skipped honestly, never thrown");
        assertEquals(4, taskStatus(view.taskId()), "terminal state persisted as usual");
    }

    @Test
    void writtenBackStateRefusesNextBatchStartAtGate() {
        long caseId = approvedCase("熔断闭环用例", "wb-fuse-" + System.nanoTime());
        withSessionScript(caseId, "回写熔断账号");
        seedState("回写熔断账号", "token", "now() + interval '3 days'");
        ClaimTaskView view = claimStartedTask(List.of(caseId), "熔断首批");
        submitHttpSnapshot(view, 401, List.of(Map.of("type", "status", "expect", 200)));
        assertEquals(0, ((Number) stateRow("回写熔断账号").get("st")).intValue(),
                "writeback landed first");
        // The failed run backfilled the case status (3→8); re-approve so the case
        // reaches the start gate again — the fuse contract under test is the
        // ledger light, not the case lifecycle.
        jdbcTemplate.update("UPDATE tp_test_case SET status = 3 WHERE id = ?", caseId);

        BatchInfo retry = draftBatch(List.of(caseId), "熔断复跑批");
        ServiceException refused = assertThrows(ServiceException.class,
                () -> batchService.start(1L, retry.id()));
        assertTrue(refused.getMessage().contains("回写熔断账号"),
                "start gate names the written-back state: " + refused.getMessage());
    }

    @Test
    void netHitsEvidenceWritesBackForCookieSetState() {
        long caseId = approvedCase("Cookie形态用例", "wb-ck-" + System.nanoTime());
        withSessionScript(caseId, "回写Cookie账号");
        seedState("回写Cookie账号", "cookie_set", "now() + interval '3 days'");
        ClaimTaskView view = claimStartedTask(List.of(caseId), "Cookie证据批");

        execTaskService.submitResult(new ExecResultInput(view.taskId(), view.claimToken(),
                "fail", 1, "页面被拒绝", List.of(), List.of(), 1500, null,
                null,
                List.of(Map.of("url", "http://127.0.0.1:8099/secret",
                        "status", "403", "durationMs", 120)),
                null, List.of(), null));

        Map<String, Object> row = stateRow("回写Cookie账号");
        assertEquals(0, ((Number) row.get("st")).intValue(),
                "string-form netHits status must also fire the writeback");
        assertTrue(String.valueOf(row.get("rk")).contains("403"),
                "remark names the code: " + row.get("rk"));
    }

    @Test
    void browserRejectionInsideLedgerScopeWritesBack() {
        long caseId = approvedCase("浏览器导航401用例", "wb-nav-401-" + System.nanoTime());
        withSessionScript(caseId, "回写导航账号");
        seedStateScoped("回写导航账号", "cookie_set", "[\"http://127.0.0.1:8099\"]");
        ClaimTaskView view = claimStartedTask(List.of(caseId), "导航证据批");

        submitBrowserRejections(view, List.of(Map.of(
                "url", "http://127.0.0.1:8099/dashboard", "status", 401)));

        Map<String, Object> row = stateRow("回写导航账号");
        assertEquals(0, ((Number) row.get("st")).intValue(),
                "a consumed state rejected on its own scope origin must turn off");
        assertTrue(String.valueOf(row.get("rk")).contains("401"), "remark names the code");
        assertEquals(4, taskStatus(view.taskId()), "terminal state untouched by the hook");
    }

    @Test
    void browserRejectionOutsideLedgerScopeKeepsState() {
        long caseId = approvedCase("范围外403用例", "wb-nav-oos-" + System.nanoTime());
        withSessionScript(caseId, "回写范围外账号");
        seedStateScoped("回写范围外账号", "cookie_set", "[\"https://other.internal\"]");
        ClaimTaskView view = claimStartedTask(List.of(caseId), "范围外批");

        submitBrowserRejections(view, List.of(Map.of(
                "url", "http://127.0.0.1:8099/admin", "status", 403)));

        Map<String, Object> row = stateRow("回写范围外账号");
        assertEquals(1, ((Number) row.get("st")).intValue(),
                "a rejection outside the state's origin whitelist says nothing about it");
    }

    /** Navigation-plane fixture: the single seedState INSERT source decides the
     * row; only the scope whitelist is re-pointed afterwards (no second INSERT
     * copy — R40 single source). */
    private void seedStateScoped(String stateName, String kind, String scopeJson) {
        seedState(stateName, kind, "now() + interval '3 days'");
        jdbcTemplate.update("UPDATE tp_login_state SET scope_origins = ?::jsonb"
                        + " WHERE project_id = ? AND state_name = ?",
                scopeJson, projectId, stateName);
    }

    /** Result relay carrying only the 34b trailing evidence plane (canonical 21-arg). */
    private void submitBrowserRejections(ClaimTaskView view, List<Map<String, Object>> rows) {
        execTaskService.submitResult(new ExecResultInput(view.taskId(), view.claimToken(),
                "fail", 1, "页面被拒绝", List.of(), List.of(), 1200, null,
                null, null, null, null, null, null, null, null, null, null, null, rows));
    }

    // ── fixture helpers ────────────────────────────────────────────────────

    private long approvedCase(String title, String caseNo) {
        return fixtures.approvedCase(projectId, title, caseNo);
    }

    private void withSessionScript(long caseId, String stateName) {
        fixtures.sessionScript(caseId, stateName);
    }

    /** Ledger row for this project; expiry is raw SQL; kind selects the state shape. */
    private void seedState(String stateName, String kind, String expirySql) {
        long credentialId = credentialService.create(projectId,
                new CredentialInput("回写凭据", "wb_cred_" + System.nanoTime(),
                        "wb-secret", null));
        jdbcTemplate.update("INSERT INTO tp_login_state (project_id, state_name, role_scope,"
                        + " account_label, kind, credential_id, scope_origins, status, expires_at)"
                        + " VALUES (?, ?, 'tester', '回写标签', ?, ?, '[\"https://wb.test.internal\"]'::jsonb, 1, "
                        + expirySql + ")",
                projectId, stateName, kind, credentialId);
    }

    private BatchInfo draftBatch(List<Long> caseIds, String name) {
        return batchService.create(1L, new BatchCreateInput(
                projectId, name, envId, caseIds, null, null, poolTag));
    }

    /** Real relay: start → register executor → claim, returns the claim envelope. */
    private ClaimTaskView claimStartedTask(List<Long> caseIds, String batchName) {
        BatchInfo batch = draftBatch(caseIds, batchName);
        batchService.start(1L, batch.id());
        long executorId = executorService.register(new ExecutorRegisterInput(
                "ex-scs-" + System.nanoTime(), String.valueOf(poolTag), null,
                "web", "chromium", 2, "v1", 0));
        // Wait-form claim: with pool isolation in place a null answer is only
        // transient dispatch jitter (soft gates tolerate ±1), so poll inside a
        // bounded budget instead of failing on the first miss.
        ClaimTaskView view = null;
        for (int attempt = 0; attempt < 50 && view == null; attempt++) {
            view = execTaskService.claim(executorId);
            if (view == null) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        assertNotNull(view, "started task must be claimable");
        return view;
    }

    private void submitHttpSnapshot(ClaimTaskView view, int status,
                                    List<Map<String, Object>> assertions) {
        List<Map<String, Object>> snapshots = List.of(Map.of(
                "stepNo", 1, "kind", "http.request",
                "request", Map.of("method", "GET", "url", "http://127.0.0.1:8099/order"),
                "response", Map.of("status", status),
                "assertions", assertions));
        execTaskService.submitResult(new ExecResultInput(view.taskId(), view.claimToken(),
                "fail", 1, "页面返回 " + status, List.of(), List.of(), 900, null,
                null, null, null, snapshots, null));
    }

    private Map<String, Object> stateRow(String stateName) {
        return jdbcTemplate.queryForMap(
                "SELECT status AS st, remark AS rk FROM tp_login_state"
                        + " WHERE project_id = ? AND state_name = ?", projectId, stateName);
    }

    private int taskStatus(long taskId) {
        Integer v = jdbcTemplate.queryForObject(
                "SELECT status FROM tp_exec_task WHERE id = ?", Integer.class, taskId);
        return v == null ? -1 : v;
    }
}
