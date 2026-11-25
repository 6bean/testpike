package com.sixbean.testpike.core.explore.domain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.executor.api.dto.ClaimTaskView;
import com.sixbean.testpike.core.executor.api.dto.ExecutorRegisterInput;
import com.sixbean.testpike.core.executor.domain.service.ExecTaskService;
import com.sixbean.testpike.core.executor.domain.service.ExecutorService;
import com.sixbean.testpike.core.explore.api.dto.ExploreCreateInput;
import com.sixbean.testpike.core.session.api.dto.LoginStateInput;
import com.sixbean.testpike.core.session.api.dto.SessionStateClaimBinding;
import com.sixbean.testpike.core.session.domain.service.LoginStateService;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Login-state wiring for the explore channel. The creation door carries two
 * namespaces with split semantics: {@code credRef} is a vault credential key
 * (mobile {@code {{CRED:}}} placeholder face only) and {@code loginStateName}
 * is a login-state ledger row name (web face). submitAct materialises ONLY the
 * loginStateName handle into the web envelope's top-level sessionState
 * declaration; a credRef value never rides that namespace again — the shared
 * claim materialisation (SessionStateDeclarationSupport + SessionStateSupport
 * single source) resolves names against tp_login_state.state_name, so mixing
 * the two namespaces used to make every web explore session anonymous.
 * <p>Zero-drift discipline asserted per shape: no loginStateName = envelope
 * byte-identical (anonymous exploration stays the honest default); an envelope
 * that already declares sessionState keeps its own selector (attach must never
 * overwrite the kernel's reference).
 * <p>Real slot database, zero mocks; fixture-owned rows cleaned in finally.
 */
@SpringBootTest(classes = CoreApplication.class)
class ExploreLoginStateChannelTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired private ExploreService exploreService;
    @Autowired private ExecTaskService execTaskService;
    @Autowired private ExecutorService executorService;
    @Autowired private LoginStateService loginStateService;
    @Autowired private JdbcTemplate jdbc;

    private long createProject(String suffix) {
        jdbc.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "wecl-" + suffix, "weclc" + suffix);
        return jdbc.queryForObject("SELECT id FROM tp_project WHERE code = ?",
                Long.class, "weclc" + suffix);
    }

    /** Ledger row so the attached name actually resolves at claim time. */
    private void registerLoginState(long projectId, String stateName) {
        String credKey = "wecl" + stateName.replaceAll("[^A-Za-z0-9_]", "");
        loginStateService.register(projectId, new LoginStateInput(
                stateName, "买家", null, "storage_state", credKey,
                "{\"cookies\":[{\"name\":\"S\",\"value\":\"v\",\"domain\":\"shop.example\","
                        + "\"path\":\"/\",\"secure\":true,\"expires\":-1}],\"origins\":[]}",
                null, List.of("https://shop.example"), "explore channel fixture"));
    }

    private long createWebSession(long projectId, String loginStateName) {
        return exploreService.create(1L, projectId, new ExploreCreateInput(
                "wecl-goal", "http://shop-" + projectId + ".test", null, null, loginStateName));
    }

    private String taskEnvelope(long taskId) {
        return jdbc.queryForObject(
                "SELECT action_json::text FROM tp_exec_task WHERE id = ?",
                String.class, taskId);
    }

    private ClaimTaskView claimWeb() {
        long exId = executorService.register(new ExecutorRegisterInput(
                "ex-wecl-" + System.nanoTime(), null, null, "web", "chromium", 2, "1.0.0", 0));
        ClaimTaskView view = execTaskService.claim(exId);
        assertNotNull(view, "a pending explore task must be claimable");
        return view;
    }

    private void cleanup(long projectId) {
        SharedDbFixtureSweep.executorsByNamePrefixes(jdbc, "ex-wecl-");
        jdbc.update("DELETE FROM tp_exec_task WHERE project_id = ?", projectId);
        jdbc.update("DELETE FROM tp_explore_session WHERE project_id = ?", projectId);
        jdbc.update("DELETE FROM tp_login_state WHERE project_id = ?", projectId);
        jdbc.update("DELETE FROM tp_credential WHERE project_id = ?", projectId);
        jdbc.update("DELETE FROM tp_project WHERE id = ?", projectId);
    }

    @Test
    void splitHandlesPersistInTheirOwnColumns() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        long projectId = createProject(suffix);
        try {
            long sessionId = exploreService.create(1L, projectId, new ExploreCreateInput(
                    "wecl-goal", "http://shop-" + projectId + ".test", null,
                    "wecl-cred-key", "wecl-buyer"));
            String stateName = jdbc.queryForObject(
                    "SELECT login_state_name FROM tp_explore_session WHERE id = ?",
                    String.class, sessionId);
            String credRef = jdbc.queryForObject(
                    "SELECT cred_ref FROM tp_explore_session WHERE id = ?",
                    String.class, sessionId);
            assertEquals("wecl-buyer", stateName,
                    "the login-state ledger handle persists in its own column");
            assertEquals("wecl-cred-key", credRef,
                    "the credential reference keeps the vault cred_key namespace");
        } finally {
            cleanup(projectId);
        }
    }

    @Test
    void submitActAttachesSessionStateFromLoginStateName() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        long projectId = createProject(suffix);
        try {
            long sessionId = createWebSession(projectId, "wecl-buyer");
            String envelope = "{\"kind\":\"explore-act\",\"action\":\"element-candidates\","
                    + "\"arguments\":{\"max\":8}}";
            long taskId = exploreService.submitAct(sessionId, envelope);
            JsonNode attached = JSON.readTree(taskEnvelope(taskId));
            assertEquals("wecl-buyer", attached.path("sessionState").path("name").asText(),
                    "the login-state handle lands as the top-level sessionState declaration");
            assertEquals("element-candidates", attached.path("action").asText(),
                    "attach is additive: the action semantics stay untouched");
        } finally {
            cleanup(projectId);
        }
    }

    @Test
    void credRefNeverFlowsIntoSessionState() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        long projectId = createProject(suffix);
        try {
            long sessionId = exploreService.create(1L, projectId, new ExploreCreateInput(
                    "wecl-goal", "http://shop-" + projectId + ".test", null,
                    "accessToken", null));
            String envelope = "{\"kind\":\"explore-act\",\"action\":\"element-candidates\","
                    + "\"arguments\":{\"max\":8}}";
            long taskId = exploreService.submitAct(sessionId, envelope);
            assertEquals(envelope, taskEnvelope(taskId),
                    "a credRef value must never mint a sessionState selector — "
                            + "without a login-state declaration the envelope stays byte-identical");
        } finally {
            cleanup(projectId);
        }
    }

    @Test
    void sessionWithoutHandleKeepsEnvelopeByteIdentical() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        long projectId = createProject(suffix);
        try {
            long sessionId = createWebSession(projectId, null);
            String envelope = "{\"kind\":\"explore-act\",\"action\":\"element-candidates\","
                    + "\"arguments\":{\"max\":8}}";
            long taskId = exploreService.submitAct(sessionId, envelope);
            assertEquals(envelope, taskEnvelope(taskId),
                    "no login-state handle = pass-through, zero drift");
        } finally {
            cleanup(projectId);
        }
    }

    @Test
    void kernelDeclaredSelectorIsNeverOverwritten() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        long projectId = createProject(suffix);
        try {
            long sessionId = createWebSession(projectId, "wecl-buyer");
            String envelope = "{\"kind\":\"explore-act\",\"action\":\"element-candidates\","
                    + "\"sessionState\":{\"roleScope\":\"买家\"},\"arguments\":{\"max\":8}}";
            long taskId = exploreService.submitAct(sessionId, envelope);
            JsonNode declared = JSON.readTree(taskEnvelope(taskId)).path("sessionState");
            assertEquals("买家", declared.path("roleScope").asText(),
                    "an existing declaration keeps its own selector");
            assertTrueish(declinedOrNull(declared.path("name")));
        } finally {
            cleanup(projectId);
        }
    }

    private static boolean declinedOrNull(JsonNode nameNode) {
        return nameNode == null || nameNode.isNull() || nameNode.isMissingNode()
                || nameNode.asText().isEmpty();
    }

    private static void assertTrueish(boolean value) {
        org.junit.jupiter.api.Assertions.assertTrue(value,
                "attach must not mint a competing name selector");
    }

    @Test
    void attachedDeclarationMaterialisesStorageStateOnClaim() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        long projectId = createProject(suffix);
        try {
            registerLoginState(projectId, "wecl-buyer");
            long sessionId = createWebSession(projectId, "wecl-buyer");
            long taskId = exploreService.submitAct(sessionId,
                    "{\"kind\":\"explore-act\",\"action\":\"element-candidates\","
                            + "\"arguments\":{\"max\":8}}");
            ClaimTaskView view = claimWeb();
            SessionStateClaimBinding bound = view.sessionState();
            assertNotNull(bound, "task_type=3 claim must materialise the attached declaration");
            assertEquals("wecl-buyer", bound.stateName());
            assertNull(bound.refusal(), "a resolvable state never carries a refusal");
            JsonNode doc = JSON.readTree(bound.storageState());
            assertEquals("S", doc.path("cookies").get(0).path("name").asText(),
                    "storageState is the resolved Playwright document (shared single source)");
            assertEquals(taskId, view.taskId(), "the claim delivered this very task");
        } finally {
            cleanup(projectId);
        }
    }

    @Test
    void undeclaredExploreTaskKeepsOldClaimShape() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        long projectId = createProject(suffix);
        try {
            long sessionId = createWebSession(projectId, null);
            exploreService.submitAct(sessionId,
                    "{\"kind\":\"explore-act\",\"action\":\"element-candidates\","
                            + "\"arguments\":{\"max\":8}}");
            assertNull(claimWeb().sessionState(),
                    "no declaration = key null, old claim shape untouched");
        } finally {
            cleanup(projectId);
        }
    }
}
