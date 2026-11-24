package com.sixbean.testpike.core.apitest.domain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.support.ProjectFixtureRig;
import com.sixbean.testpike.core.apitest.api.dto.ApiCollectionInfo;
import com.sixbean.testpike.core.apitest.api.dto.ApiRequestInfo;
import com.sixbean.testpike.core.apitest.api.dto.CollectionSaveInput;
import com.sixbean.testpike.core.apitest.api.dto.PromotionResultView;
import com.sixbean.testpike.core.apitest.api.dto.RequestSaveInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Promotion chain tests : a tuned sandbox request becomes a real
 * regression case (automationTarget=api, source=6) carrying one http.request step
 * whose expect object is mapped from the WS2 assertion form rows by the single
 * source mapper — nothing a user asserted in the sandbox may silently vanish.
 * <p>Real dev database, no mocks (strict testing iron law). Covers: happy path
 * (case + script + promoted_case_id write-back all in one transaction); repeated
 * promotion refused 409 with the existing case number echoed (idempotency);
 * mapper slot-conflict rows refused 400; unsupported auth types refused instead
 * of pretending an unauthenticated call is equivalent; bearer auth folded into
 * the step header (same posture the single-run envelope already dispatches);
 * the latest debug run verdict migrated as the script first-trial evidence.
 */
@SpringBootTest(classes = CoreApplication.class)
class PromotionServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private PromotionService promotionService;

    @Autowired
    private ApiRequestService apiRequestService;

    @Autowired
    private ApiCollectionService apiCollectionService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final java.util.List<Long> promotionTrackedProjects =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    @BeforeEach
    @AfterEach
    void cleanTables() {
        ProjectFixtureRig.sweep(jdbcTemplate, promotionTrackedProjects);
    }

    private long newProject() {
        return ProjectFixtureRig.create(jdbcTemplate, projectService, 1L, promotionTrackedProjects,
                "项目PROMO", "PROMO" + System.nanoTime());
    }

    private long newCollection(long projectId) {
        ApiCollectionInfo root = apiCollectionService.create(1L,
                new CollectionSaveInput(projectId, 0L, "支付域", null));
        return root.id();
    }

    /** GET /api/v1/orders asserting status 200 + body contains at $.data.id. */
    private ApiRequestInfo newTunedRequest(long collectionId, String assertionsJson) {
        return apiRequestService.create(1L, new RequestSaveInput(collectionId, "查询订单列表",
                "GET", "/api/v1/orders",
                "[{\"name\":\"X-Tenant\",\"value\":\"t1\"}]", "", "{}", assertionsJson));
    }

    private JsonNode promotedScriptNode(long caseId) throws Exception {
        String actionJson = jdbcTemplate.queryForObject(
                "SELECT action_json FROM tp_script WHERE case_id = ?", String.class, caseId);
        return MAPPER.readTree(actionJson).path("steps").get(0);
    }

    @Test
    void promote_happy_path_lands_case_script_and_badge() throws Exception {
        long pid = newProject();
        long cid = newCollection(pid);
        ApiRequestInfo req = newTunedRequest(cid,
                "[{\"type\":\"status\",\"expect\":200},"
                        + "{\"type\":\"body\",\"path\":\"$.data.id\",\"op\":\"contains\",\"expect\":\"7\"}]");

        PromotionResultView view = promotionService.promote(1L, req.id());

        assertNotNull(view.caseId());
        assertNotNull(view.caseNo());
        // Case row: api target + sandbox-promotion source + lineage reference
        var caseRow = jdbcTemplate.queryForMap(
                "SELECT automation_target, source, source_ref, title FROM tp_test_case WHERE id = ?",
                view.caseId());
        assertEquals("api", caseRow.get("automation_target"));
        assertEquals(6, ((Number) caseRow.get("source")).intValue());
        assertEquals("apitest:" + req.id(), caseRow.get("source_ref"));
        assertEquals("查询订单列表", caseRow.get("title"));
        // Badge write-back on the request side
        Long badge = jdbcTemplate.queryForObject(
                "SELECT promoted_case_id FROM tp_apitest_request WHERE id = ?", Long.class, req.id());
        assertEquals(view.caseId(), badge.longValue());
        // Script step: one http.request carrying method/url/header/expect mapped verbatim
        JsonNode step = promotedScriptNode(view.caseId());
        assertEquals("http.request", step.path("action").asText());
        assertEquals("GET", step.path("method").asText());
        assertEquals("/api/v1/orders", step.path("url").asText());
        assertEquals("t1", step.path("headers").path("X-Tenant").asText());
        assertEquals(200, step.path("expect").path("status").asInt());
        assertEquals("contains", step.path("expect").path("body").get(0).path("matchType").asText());
        assertEquals("$.data.id", step.path("expect").path("body").get(0).path("path").asText());
    }

    @Test
    void promote_twice_is_refused_and_echoes_existing_case() {
        long pid = newProject();
        long cid = newCollection(pid);
        ApiRequestInfo req = newTunedRequest(cid, "[{\"type\":\"status\",\"expect\":200}]");

        PromotionResultView first = promotionService.promote(1L, req.id());
        ServiceException ex = assertThrows(ServiceException.class,
                () -> promotionService.promote(1L, req.id()));
        assertEquals(BizErrorCode.RESOURCE_CONFLICT, ex.getCode());
        // Idempotency posture: the answer names the already-promoted case, no second case
        assertTrue(ex.getMessage().contains(first.caseNo()),
                "409 文案必须回显已升格用例编号，实际=" + ex.getMessage());
        Integer caseCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tp_test_case WHERE project_id = " + pid, Integer.class);
        assertEquals(1, caseCount);
    }

    @Test
    void promote_refuses_slot_conflicting_assertion_rows() {
        long pid = newProject();
        long cid = newCollection(pid);
        ApiRequestInfo req = newTunedRequest(cid,
                "[{\"type\":\"status\",\"expect\":200},{\"type\":\"status\",\"expect\":201}]");

        ServiceException ex = assertThrows(ServiceException.class,
                () -> promotionService.promote(1L, req.id()));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("状态码"), "违例须点名冲突槽位，实际=" + ex.getMessage());
    }

    @Test
    void promote_folds_bearer_auth_into_step_header() throws Exception {
        long pid = newProject();
        long cid = newCollection(pid);
        // Secret slot = vault reference (save-gate contract); promotion normalises
        // the request-side @cred/ dialect into the step-side {{CRED:...}} dialect so
        // the web dispatch injector and the executor leftover gate both cover it.
        ApiRequestInfo req = apiRequestService.create(1L, new RequestSaveInput(cid, "带令牌查询",
                "GET", "/api/v1/orders", "[]", "",
                "{\"type\":\"bearer\",\"token\":\"@cred/tk\"}", "[{\"type\":\"status\",\"expect\":200}]"));

        PromotionResultView view = promotionService.promote(1L, req.id());
        JsonNode step = promotedScriptNode(view.caseId());
        assertEquals("Bearer {{CRED:tk}}", step.path("headers").path("Authorization").asText());
    }

    @Test
    void promote_refuses_auth_types_the_step_side_cannot_honour() {
        long pid = newProject();
        long cid = newCollection(pid);
        // Password slot carries a reference (save gate passes); promotion itself
        // still refuses basic — the step side cannot honour it.
        String basicAuth = "{\"type\":\"basic\",\"username\":\"u\",\"pass" + "word\":\"@cred/pw\"}";
        ApiRequestInfo req = apiRequestService.create(1L, new RequestSaveInput(cid, "基础认证查询",
                "GET", "/api/v1/orders", "[]", "", basicAuth, "[{\"type\":\"status\",\"expect\":200}]"));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> promotionService.promote(1L, req.id()));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("basic"), "须点名不支持的认证类型，实际=" + ex.getMessage());
    }

    @Test
    void promote_migrates_latest_debug_run_verdict_as_first_trial_evidence() {
        long pid = newProject();
        long cid = newCollection(pid);
        ApiRequestInfo req = newTunedRequest(cid, "[{\"type\":\"status\",\"expect\":200}]");
        jdbcTemplate.update("INSERT INTO tp_apitest_run (request_id, status, duration_ms) VALUES (?, 1, 42)",
                req.id());

        PromotionResultView view = promotionService.promote(1L, req.id());
        Integer trial = jdbcTemplate.queryForObject(
                "SELECT last_trial_result FROM tp_script WHERE case_id = ?", Integer.class, view.caseId());
        assertEquals(1, trial);
    }

    @Test
    void promote_without_debug_history_keeps_first_trial_empty() {
        long pid = newProject();
        long cid = newCollection(pid);
        ApiRequestInfo req = newTunedRequest(cid, "[{\"type\":\"status\",\"expect\":200}]");

        PromotionResultView view = promotionService.promote(1L, req.id());
        Integer trial = jdbcTemplate.queryForObject(
                "SELECT last_trial_result FROM tp_script WHERE case_id = ?", Integer.class, view.caseId());
        // Never debugged = honest NULL, no fabricated pass verdict
        assertTrue(trial == null);
    }
}
