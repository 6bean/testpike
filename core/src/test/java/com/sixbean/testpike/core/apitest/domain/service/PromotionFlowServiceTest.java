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
import com.sixbean.testpike.core.apitest.api.dto.FlowPromotionResultView;
import com.sixbean.testpike.core.apitest.api.dto.PromotionResultView;
import com.sixbean.testpike.core.apitest.api.dto.RequestSaveInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Flow-level promotion tests : a linear request chain of one
 * collection becomes ONE regression case carrying one http.request step per
 * member in the same order the run-set preview draws (single source — the flow
 * the user sees is the flow that gets promoted).
 * <p>Real dev database, no mocks (strict testing iron law). Covers: happy path
 * (two chained requests, extract slot on the producer step, {{VAR:...}}
 * reference carried verbatim into the consumer step, badges on every member);
 * orchestration rows (precondition/loop) refused by name — the case-side 04
 * vocabulary is not isomorphic, so promotion fails closed instead of pretending;
 * ws members refused for the same honesty reason; a member already promoted is
 * refused with the existing case number; an empty collection is refused with
 * plain words; a refused promotion leaves no orphan case (all-or-nothing).
 */
@SpringBootTest(classes = CoreApplication.class)
class PromotionFlowServiceTest {

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

    private final java.util.List<Long> promotionFlowTrackedProjects =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    @BeforeEach
    @AfterEach
    void cleanTables() {
        ProjectFixtureRig.sweep(jdbcTemplate, promotionFlowTrackedProjects);
    }

    private long newProject() {
        // Code cap = 20 chars (ProjectServiceImpl gate); keep the prefix short so
        // the nanoTime tail always fits.
        return ProjectFixtureRig.create(jdbcTemplate, projectService, 1L, promotionFlowTrackedProjects,
                "项目PROMOFLOW", "PF" + System.nanoTime());
    }

    private long newCollection(long projectId) {
        ApiCollectionInfo root = apiCollectionService.create(1L,
                new CollectionSaveInput(projectId, 0L, "下单流程", null));
        return root.id();
    }

    /** Producer step: creates an order and extracts the id into the pool. */
    private ApiRequestInfo newProducer(long collectionId) {
        return apiRequestService.create(1L, new RequestSaveInput(collectionId, "创建订单",
                "POST", "/api/v1/orders", "[]", "{\"sku\":\"A1\"}", "{}",
                "[{\"type\":\"status\",\"expect\":201},"
                        + "{\"type\":\"extract\",\"path\":\"$.data.id\",\"saveAs\":\"ORDER_ID\"}]"));
    }

    /** Consumer step: reads the order back through the {{VAR:...}} reference. */
    private ApiRequestInfo newConsumer(long collectionId) {
        return apiRequestService.create(1L, new RequestSaveInput(collectionId, "查询订单",
                "GET", "/api/v1/orders/detail",
                "[{\"name\":\"X-Order\",\"value\":\"{{VAR:ORDER_ID}}\"}]", "", "{}",
                "[{\"type\":\"status\",\"expect\":200}]"));
    }

    private JsonNode promotedSteps(long caseId) throws Exception {
        String actionJson = jdbcTemplate.queryForObject(
                "SELECT action_json FROM tp_script WHERE case_id = ?", String.class, caseId);
        return MAPPER.readTree(actionJson).path("steps");
    }

    @Test
    void promoteFlow_happy_path_lands_one_case_with_ordered_steps() throws Exception {
        long pid = newProject();
        long cid = newCollection(pid);
        ApiRequestInfo producer = newProducer(cid);
        ApiRequestInfo consumer = newConsumer(cid);

        FlowPromotionResultView view = promotionService.promoteFlow(1L, cid);

        assertNotNull(view.caseNo());
        assertEquals(cid, view.collectionId());
        assertEquals(pid, view.projectId());
        assertEquals(2, view.promotedRequestCount());
        // One case, api target, promotion source, lineage on the collection
        var caseRow = jdbcTemplate.queryForMap(
                "SELECT automation_target, source, source_ref, title FROM tp_test_case WHERE id = ?",
                view.caseId());
        assertEquals("api", caseRow.get("automation_target"));
        assertEquals(6, ((Number) caseRow.get("source")).intValue());
        assertEquals("apitest-flow:" + cid, caseRow.get("source_ref"));
        assertEquals("下单流程", caseRow.get("title"));
        // Case step rows mirror the flow rows (one operation step per member)
        Integer stepRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tp_case_step WHERE case_id = ?", Integer.class, view.caseId());
        assertEquals(2, stepRows);
        // Script steps: same order the preview draws; producer keeps the extract
        // slot, consumer keeps the raw {{VAR:...}} reference (executor resolves it)
        JsonNode steps = promotedSteps(view.caseId());
        assertEquals(2, steps.size());
        assertEquals("http.request", steps.get(0).path("action").asText());
        assertEquals("POST", steps.get(0).path("method").asText());
        assertEquals("ORDER_ID", steps.get(0).path("saveAs").asText());
        assertEquals(201, steps.get(0).path("expect").path("status").asInt());
        assertEquals("GET", steps.get(1).path("method").asText());
        assertEquals("{{VAR:ORDER_ID}}",
                steps.get(1).path("headers").path("X-Order").asText());
        // Badges: every member points at the same promoted case
        for (ApiRequestInfo member : List.of(producer, consumer)) {
            Long badge = jdbcTemplate.queryForObject(
                    "SELECT promoted_case_id FROM tp_apitest_request WHERE id = ?",
                    Long.class, member.id());
            assertNotNull(badge);
            assertEquals(view.caseId(), badge.longValue());
        }
    }

    @Test
    void promoteFlow_refuses_orchestration_rows_naming_the_member() {
        long pid = newProject();
        long cid = newCollection(pid);
        newProducer(cid);
        // Loop row = repeat policy the 04 step vocabulary cannot carry verbatim
        apiRequestService.create(1L, new RequestSaveInput(cid, "轮询订单状态",
                "GET", "/api/v1/orders/status", "[]", "", "{}",
                "[{\"type\":\"status\",\"expect\":200}]",
                null, "{\"mode\":\"count\",\"times\":3}"));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> promotionService.promoteFlow(1L, cid));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("轮询订单状态"),
                "违例须点名带编排行的请求，实际=" + ex.getMessage());
        // All-or-nothing: the refusal leaves no orphan case/script behind
        Integer caseCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tp_test_case WHERE project_id = " + pid, Integer.class);
        assertEquals(0, caseCount);
    }

    @Test
    void promoteFlow_refuses_precondition_rows_naming_the_member() {
        long pid = newProject();
        long cid = newCollection(pid);
        apiRequestService.create(1L, new RequestSaveInput(cid, "条件出单",
                "POST", "/api/v1/orders", "[]", "", "{}", "[]",
                "{\"field\":\"status\",\"op\":\"eq\",\"expect\":200}", null));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> promotionService.promoteFlow(1L, cid));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("条件出单"),
                "违例须点名带前置条件的请求，实际=" + ex.getMessage());
    }

    @Test
    void promoteFlow_refuses_ws_members_for_the_same_honesty_reason() {
        long pid = newProject();
        long cid = newCollection(pid);
        newProducer(cid);
        ApiRequestInfo wsMember = apiRequestService.create(1L, new RequestSaveInput(cid, "订阅行情",
                "GET", "/ws/quote", "[]", "", "{}", "[]"));
        // protocol column is save-gated vocabulary; land the ws discriminator the
        // way the transport segment does (direct row update keeps this test focused)
        jdbcTemplate.update("UPDATE tp_apitest_request SET protocol = 'ws' WHERE id = ?", wsMember.id());

        ServiceException ex = assertThrows(ServiceException.class,
                () -> promotionService.promoteFlow(1L, cid));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("订阅行情"),
                "违例须点名 ws 成员，实际=" + ex.getMessage());
    }

    @Test
    void promoteFlow_refuses_when_a_member_is_already_promoted() {
        long pid = newProject();
        long cid = newCollection(pid);
        ApiRequestInfo producer = newProducer(cid);
        newConsumer(cid);
        PromotionResultView single = promotionService.promote(1L, producer.id());

        ServiceException ex = assertThrows(ServiceException.class,
                () -> promotionService.promoteFlow(1L, cid));
        assertEquals(BizErrorCode.RESOURCE_CONFLICT, ex.getCode());
        assertTrue(ex.getMessage().contains(single.caseNo()),
                "409 文案须回显已升格用例编号，实际=" + ex.getMessage());
        // No second case for the rest of the flow either
        Integer caseCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tp_test_case WHERE project_id = " + pid, Integer.class);
        assertEquals(1, caseCount);
    }

    @Test
    void promoteFlow_refuses_empty_collection_in_plain_words() {
        long pid = newProject();
        long cid = newCollection(pid);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> promotionService.promoteFlow(1L, cid));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("请求"),
                "空流程拒绝须是人话，实际=" + ex.getMessage());
    }

    @Test
    void promoteFlow_keeps_first_trial_null_when_no_member_ever_ran() {
        long pid = newProject();
        long cid = newCollection(pid);
        newProducer(cid);
        newConsumer(cid);

        FlowPromotionResultView view = promotionService.promoteFlow(1L, cid);
        Integer trial = jdbcTemplate.queryForObject(
                "SELECT last_trial_result FROM tp_script WHERE case_id = ?", Integer.class, view.caseId());
        // Never debugged = honest NULL (no fabricated pass for a whole flow)
        assertNull(trial);
    }

    @Test
    void promoteFlow_migrates_verdict_only_when_every_member_has_run_evidence() {
        long pid = newProject();
        long cid = newCollection(pid);
        ApiRequestInfo producer = newProducer(cid);
        ApiRequestInfo consumer = newConsumer(cid);
        jdbcTemplate.update("INSERT INTO tp_apitest_run (request_id, status, duration_ms) VALUES (?, 1, 30)",
                producer.id());
        jdbcTemplate.update("INSERT INTO tp_apitest_run (request_id, status, duration_ms) VALUES (?, 2, 45)",
                consumer.id());

        FlowPromotionResultView view = promotionService.promoteFlow(1L, cid);
        Integer trial = jdbcTemplate.queryForObject(
                "SELECT last_trial_result FROM tp_script WHERE case_id = ?", Integer.class, view.caseId());
        // One member's latest run failed = the flow verdict is a failure, honestly
        assertEquals(2, trial);
    }
}
