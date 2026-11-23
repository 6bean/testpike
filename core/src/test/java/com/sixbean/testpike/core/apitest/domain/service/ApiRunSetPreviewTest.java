package com.sixbean.testpike.core.apitest.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.support.ProjectFixtureRig;
import com.sixbean.testpike.core.apitest.api.dto.ApiCollectionInfo;
import com.sixbean.testpike.core.apitest.api.dto.ApiRequestInfo;
import com.sixbean.testpike.core.apitest.api.dto.ApiRunSetPreviewStep;
import com.sixbean.testpike.core.apitest.api.dto.CollectionSaveInput;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Order-preview read-model for a collection run .
 * <p>The preview is the launch-dialog data source: one row per request in tree
 * order, each carrying the pool keys it extracts and the intra-collection edges
 * (key + producer step) the sequence card draws as dependency arrows. The
 * dependency judgement must come from the SAME single source the advancer uses
 * (ApiRunSetPlanSupport over the pinned grammar) — a frontend re-parse would be
 * a second {{VAR:...}} parser, forbidden by the red line.
 */
@SpringBootTest(classes = CoreApplication.class)
class ApiRunSetPreviewTest {

    @Autowired
    private ApiRunSetService apiRunSetService;

    @Autowired
    private ApiRequestService apiRequestService;

    @Autowired
    private ApiCollectionService apiCollectionService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final java.util.List<Long> setPreviewTrackedProjects =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    @BeforeEach
    @AfterEach
    void cleanTables() {
        ProjectFixtureRig.sweep(jdbcTemplate, setPreviewTrackedProjects);
    }

    private long createPreviewProject(String code) {
        return ProjectFixtureRig.createSelfHeal(jdbcTemplate, projectService, 1L,
                setPreviewTrackedProjects, "项目" + code, code);
    }

    private ApiCollectionInfo createCollection(long projectId, String name) {
        return apiCollectionService.create(1L, new CollectionSaveInput(projectId, 0L, name, null));
    }

    private ApiRequestInfo createRequest(long collectionId, String name, String method,
                                         String path, String body, String assertions) {
        return apiRequestService.create(1L, new RequestSaveInput(collectionId, name, method,
                path, null, body, null, assertions));
    }

    private static String extractRow(String saveAs) {
        return "[{\"type\":\"extract\",\"saveAs\":\"" + saveAs + "\",\"path\":\"$.data.token\"}]";
    }

    @Test
    void preview_链式集合按树序出行_依赖边点名生产者() {
        long projectId = createPreviewProject("PV1");
        ApiCollectionInfo collection = createCollection(projectId, "下单链");
        ApiRequestInfo login = createRequest(collection.id(), "登录", "POST", "/auth/login",
                null, extractRow("token"));
        ApiRequestInfo order = createRequest(collection.id(), "下单", "POST", "/orders",
                "{\"t\":\"{{VAR:token}}\"}", null);
        ApiRequestInfo health = createRequest(collection.id(), "健康检查", "GET", "/health",
                null, null);

        List<ApiRunSetPreviewStep> steps = apiRunSetService.preview(collection.id());

        assertEquals(3, steps.size());
        assertEquals(List.of(1, 2, 3), steps.stream().map(ApiRunSetPreviewStep::seqNo).toList());
        assertEquals(login.id(), steps.get(0).requestId());
        assertEquals("登录", steps.get(0).name());
        assertEquals(List.of("token"), steps.get(0).extracts());
        assertTrue(steps.get(0).deps().isEmpty(), "producer step has no upstream edge");

        assertEquals(order.id(), steps.get(1).requestId());
        assertEquals(1, steps.get(1).deps().size());
        assertEquals("token", steps.get(1).deps().get(0).key());
        assertEquals(login.id(), steps.get(1).deps().get(0).producerRequestId(),
                "the arrow must name the exact producer row, not just the key");

        assertTrue(steps.get(2).deps().isEmpty(),
                "independent step carries no dependency edge");
    }

    @Test
    void preview_引用无集合内生产者的键_不进边表只进引用() {
        long projectId = createPreviewProject("PV2");
        ApiCollectionInfo collection = createCollection(projectId, "外部变量");
        // ghost has no extracting step inside the collection (pool/global origin).
        createRequest(collection.id(), "查单", "GET", "/orders/1",
                "{\"uid\":\"{{VAR:ghost}}\"}", null);

        List<ApiRunSetPreviewStep> steps = apiRunSetService.preview(collection.id());

        assertEquals(1, steps.size());
        assertTrue(steps.get(0).deps().isEmpty(),
                "keys produced outside the collection never fabricate an arrow");
        assertTrue(steps.get(0).references().contains("ghost"),
                "the raw reference set still shows where the value is consumed");
    }

    @Test
    void preview_前向引用边照画并标注生产者在后() {
        long projectId = createPreviewProject("PV4");
        ApiCollectionInfo collection = createCollection(projectId, "倒序链");
        // Consumer sits BEFORE the producer in tree order — the run will hit an
        // empty pool value; the card must draw the arrow and flag the ordering.
        ApiRequestInfo user = createRequest(collection.id(), "下单", "POST", "/orders",
                "{\"t\":\"{{VAR:token}}\"}", null);
        ApiRequestInfo producer = createRequest(collection.id(), "登录", "POST", "/auth/login",
                null, extractRow("token"));

        List<ApiRunSetPreviewStep> steps = apiRunSetService.preview(collection.id());

        assertEquals(1, steps.get(0).deps().size());
        assertEquals(producer.id(), steps.get(0).deps().get(0).producerRequestId());
        assertEquals(false, steps.get(0).deps().get(0).producerEarlier(),
                "producer sits after the consumer — honest ordering flag");
        assertEquals(true, steps.get(1).deps().isEmpty() || steps.get(1).extracts().equals(List.of("token")));
    }

    @Test
    void preview_空集合诚实返回空列表不谎报() {
        long projectId = createPreviewProject("PV3");
        ApiCollectionInfo collection = createCollection(projectId, "还没放请求");
        assertTrue(apiRunSetService.preview(collection.id()).isEmpty());
    }

    @Test
    void preview_集合不存在404人话() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> apiRunSetService.preview(999999999L));
        assertEquals(BizErrorCode.NOT_FOUND, ex.getCode().intValue(),
                "missing collection surfaces NOT_FOUND (ownership single point reuses get())");
    }
}
