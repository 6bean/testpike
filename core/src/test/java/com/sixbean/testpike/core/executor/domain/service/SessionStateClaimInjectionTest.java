package com.sixbean.testpike.core.executor.domain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.executor.api.dto.ClaimTaskView;
import com.sixbean.testpike.core.executor.api.dto.ExecTaskCreateInput;
import com.sixbean.testpike.core.executor.api.dto.ExecutorRegisterInput;
import com.sixbean.testpike.core.session.api.dto.LoginStateInput;
import com.sixbean.testpike.core.session.api.dto.SessionStateClaimBinding;
import com.sixbean.testpike.core.session.domain.service.LoginStateService;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Claim-side login-state injection for browser tasks ( face 2, 03 protocol
 * 2.3 trailing sessionState key).
 * <p>A task_type=1 claim whose action JSON declares a sessionState selector
 * carries {stateName,kind,storageState} resolved by SessionStateSupport — the
 * single translation source shared with the direct-API face (red line 6, the
 * executor never re-derives). Declaration absent = key null: old claim shape
 * untouched (zero-drift trailing discipline, same as mock/perf).
 * <p>Honest refusal is part of the contract: a selector naming a row that does
 * not exist (or an ambiguous role) must NOT throw — throwing would poison the
 * whole claim round for every other task. The envelope instead carries
 * refusal=&lt;plain remedy&gt; with storageState null, and the executor fails
 * just this task with that wording (session_context pass-through).
 * <p>Real slot6 database, zero mocks; fixture-owned rows cleaned in finally.
 */
@SpringBootTest(classes = CoreApplication.class)
class SessionStateClaimInjectionTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired private ExecTaskService execTaskService;
    @Autowired private ExecutorService executorService;
    @Autowired private LoginStateService loginStateService;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void sweepOwnResidue() {
        // Claim picks the oldest pending task, so a stale row from an aborted
        // run of THIS suite must not hijack the queue head. R37 discipline
        // forbids the blanket TRUNCATE posture here: tp_exec_task/tp_executor
        // are shared factory tables and CASCADE would wipe rows owned by
        // concurrent suites (truncate_ratchet ledger counts that debt). The
        // compliant shape sweeps only this suite's naming family — fixture
        // projects 'ssclaim-*' via the shared FK-tree helper, executors
        // 'ex-ss-*' — and stays a no-op on a clean queue.
        List<Long> ownProjects = jdbc.query(
                "SELECT id FROM tp_project WHERE name LIKE 'ssclaim-%'",
                (rs, i) -> rs.getLong(1));
        SharedDbFixtureSweep.byProjectList(jdbc, ownProjects);
        jdbc.update("DELETE FROM tp_executor WHERE name LIKE 'ex-ss-%'");
    }

    private record Fixture(long projectId, long envId, long caseId) {
    }

    private Fixture createFixture(String actionJson) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        jdbc.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "ssclaim-" + suffix, "ssc" + suffix);
        long projectId = jdbc.queryForObject("SELECT id FROM tp_project WHERE code = ?",
                Long.class, "ssc" + suffix);
        long envId = jdbc.queryForObject("INSERT INTO tp_project_env (project_id, name, base_url,"
                        + " config_json) VALUES (?, ?, ?, '{}'::jsonb) RETURNING id",
                Long.class, projectId, "env-" + suffix, "http://shop-" + projectId + ".test");
        long caseId = jdbc.queryForObject("INSERT INTO tp_test_case (case_no, project_id, title)"
                        + " VALUES (?, ?, ?) RETURNING id",
                Long.class, "SSCLM-" + UUID.randomUUID().toString().substring(0, 20),
                projectId, "session-bound browser case");
        execTaskService.create(new ExecTaskCreateInput(projectId, caseId, "v2", actionJson, envId));
        return new Fixture(projectId, envId, caseId);
    }

    /** Ledger row in manual Playwright grammar (pass-through shape). */
    private void register(long projectId, String stateName, String roleScope) {
        // Vault keys accept ASCII [A-Za-z_][A-Za-z0-9_]* only — state name
        // stripped to ASCII, same fixture pitfall recorded for the face-3 suite.
        String credKey = "ssc" + stateName.replaceAll("[^A-Za-z0-9_]", "");
        loginStateService.register(projectId, new LoginStateInput(
                stateName, roleScope, null, "storage_state", credKey,
                "{\"cookies\":[{\"name\":\"S\",\"value\":\"v\",\"domain\":\"shop.example\","
                        + "\"path\":\"/\",\"secure\":true,\"expires\":-1}],\"origins\":[]}",
                null, List.of("https://shop.example"), "claim fixture"));
    }

    private ClaimTaskView claimOne() {
        long exId = executorService.register(new ExecutorRegisterInput(
                "ex-ss-" + System.nanoTime(), null, null, "web", "chromium", 2, "1.0.0", 0));
        ClaimTaskView view = execTaskService.claim(exId);
        assertNotNull(view, "a pending task must be claimable");
        return view;
    }

    private void cleanup(Fixture f) {
        jdbc.update("DELETE FROM tp_exec_task WHERE project_id = ?", f.projectId());
        jdbc.update("DELETE FROM tp_test_case WHERE id = ?", f.caseId());
        jdbc.update("DELETE FROM tp_login_state WHERE project_id = ?", f.projectId());
        jdbc.update("DELETE FROM tp_credential WHERE project_id = ?", f.projectId());
        jdbc.update("DELETE FROM tp_project_env WHERE id = ?", f.envId());
        jdbc.update("DELETE FROM tp_project WHERE id = ?", f.projectId());
    }

    @Test
    void declaredNameMaterialisesStorageStateOnClaim() throws Exception {
        Fixture f = createFixture("{\"steps\":[],\"sessionState\":{\"name\":\"ssc-buyer\"}}");
        try {
            register(f.projectId(), "ssc-buyer", "买家");
            SessionStateClaimBinding bound = claimOne().sessionState();
            assertNotNull(bound, "declared selector must surface the envelope");
            assertEquals("ssc-buyer", bound.stateName());
            assertEquals("storage_state", bound.kind());
            assertNull(bound.refusal(), "resolvable state never carries a refusal");
            JsonNode doc = JSON.readTree(bound.storageState());
            assertEquals("S", doc.path("cookies").get(0).path("name").asText(),
                    "storageState is the resolved Playwright document");
        } finally {
            cleanup(f);
        }
    }

    @Test
    void declaredRoleScopeResolvesUniqueRow() {
        Fixture f = createFixture("{\"steps\":[],\"sessionState\":{\"roleScope\":\"审批人\"}}");
        try {
            register(f.projectId(), "ssc-approver", "审批人");
            SessionStateClaimBinding bound = claimOne().sessionState();
            assertNotNull(bound);
            assertEquals("ssc-approver", bound.stateName(), "role scope resolves via the ledger");
            assertNotNull(bound.storageState());
        } finally {
            cleanup(f);
        }
    }

    @Test
    void undeclaredTaskKeepsOldClaimShape() {
        Fixture f = createFixture("{\"steps\":[]}");
        try {
            assertNull(claimOne().sessionState(), "no declaration = key null, zero drift");
        } finally {
            cleanup(f);
        }
    }

    @Test
    void unknownSelectorRefusesHonestlyWithoutPoisoningClaim() {
        Fixture f = createFixture("{\"steps\":[],\"sessionState\":{\"name\":\"ssc-ghost\"}}");
        try {
            ClaimTaskView view = claimOne(); // must not throw: one bad row cannot block the queue
            SessionStateClaimBinding bound = view.sessionState();
            assertNotNull(bound, "a declaration always yields a decision, never silence");
            assertNull(bound.storageState(), "unresolvable = no material");
            assertNotNull(bound.refusal());
            assertTrue(bound.refusal().contains("ssc-ghost"), "refusal names the wanted row: " + bound.refusal());
            assertTrue(bound.refusal().contains("登录态"), "refusal is in plain user language: " + bound.refusal());
        } finally {
            cleanup(f);
        }
    }
}
