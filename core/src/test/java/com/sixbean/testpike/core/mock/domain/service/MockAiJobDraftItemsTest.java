package com.sixbean.testpike.core.mock.domain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import com.sixbean.testpike.core.executor.domain.component.ArtifactStorage;
import com.sixbean.testpike.core.mock.api.controller.MockAiJobInternalController;
import com.sixbean.testpike.core.mock.api.dto.MockAiJobCreateInput;
import com.sixbean.testpike.core.mock.api.dto.MockAiJobDraftItemView;
import com.sixbean.testpike.core.mock.api.dto.MockDraftArtifactGrantView;
import com.sixbean.testpike.core.mock.api.dto.MockSpaceInput;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Draft read-back door of AI drafting jobs : the bundle a
 * model PUTs through the artifact grant is currently write-only — the preview
 * confirmation drawer has no way to read the ticked items back. This door
 * closes that gap: preview/consumed jobs read their bundle items as typed
 * views, queued/running/failed/cancelled reads are refused in plain words,
 * and a vanished artifact is refused honestly instead of an empty list.
 * <p>Contract under test mirrors the write side (MockDraftGenServiceImpl):
 * bundle root keys items + jobType, item keys name/method/path/pathMatchType/
 * responseJson/scenarioName?/fault?/sourceAnchor.
 * <p>Real dev database + real S3, zero mocks (MockAiJobArtifactGrantTest
 * posture); fixtures cleaned in finally with zero residue.
 */
@SpringBootTest(classes = CoreApplication.class)
class MockAiJobDraftItemsTest {

    private static final String MASTER_KEY = "ai.mock.enabled";
    private static final String NL_STUB_KEY = "ai.mock.nl-stub";

    @Autowired
    private MockAiJobService aiJobService;

    @Autowired
    private MockAiJobInternalController controller;

    @Autowired
    private MockSpaceService spaceService;

    @Autowired
    private ConfigCacheHolder configCacheHolder;

    @Autowired
    private ArtifactStorage artifactStorage;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<String> cleanupKeys = new ArrayList<>();

    private long newProject() {
        String code = "t365d" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        return jdbcTemplate.queryForObject(
                "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1) RETURNING id",
                Long.class, code, code);
    }

    private long newSpace(long projectId) {
        String key = "t365d-" + UUID.randomUUID().toString().substring(0, 10);
        return spaceService.create(projectId,
                new MockSpaceInput("Space " + key, key, null, null,
                        "prefix", "none", null, null, 100, null));
    }

    /** PUT the draft bundle through the real grant door, exactly as the consumer does. */
    private String uploadBundle(long jobId, String bundleJson) throws Exception {
        MockDraftArtifactGrantView grant = aiJobService.artifactGrant(jobId, "draft.json");
        cleanupKeys.add(grant.objectKey());
        HttpResponse<Void> put = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(grant.uploadUrl()))
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(
                                bundleJson.getBytes(StandardCharsets.UTF_8)))
                        .header("Content-Type", "application/json")
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(200, put.statusCode(), "presigned PUT must accept the draft bundle");
        return grant.objectKey();
    }

    private String bundleOf(ObjectNode... items) {
        ObjectNode bundle = objectMapper.createObjectNode();
        ArrayNode arr = bundle.putArray("items");
        for (ObjectNode item : items) {
            arr.add(item);
        }
        bundle.put("jobType", "nl-stub");
        return bundle.toString();
    }

    private ObjectNode stubItem(String name, String method, String path, String responseJson) {
        ObjectNode item = objectMapper.createObjectNode();
        item.put("name", name);
        item.put("method", method);
        item.put("path", path);
        item.put("pathMatchType", "exact");
        item.put("responseJson", responseJson);
        item.put("sourceAnchor", name);
        return item;
    }

    /** preview job: bundle in storage reads back as typed items, all fields aligned. */
    @Test
    void previewJobReadsBackBundleItemsWithFullFieldSpectrum() throws Exception {
        long projectId = newProject();
        long spaceId = newSpace(projectId);
        long jobId = 0;
        try {
            configCacheHolder.put(MASTER_KEY, "1");
            configCacheHolder.put(NL_STUB_KEY, "1");
            jobId = aiJobService.createJob(new MockAiJobCreateInput(spaceId, "nl-stub",
                    "订单接口超时返回5001")).id();

            ObjectNode plain = stubItem("订单查询桩", "GET", "/orders/{id}", "{\"status\":200}");
            ObjectNode withScenario = stubItem("订单创建桩", "POST", "/orders", "{\"status\":201}");
            withScenario.put("scenarioName", "订单状态机");
            String ref = uploadBundle(jobId, bundleOf(plain, withScenario));

            aiJobService.markRunning(jobId);
            aiJobService.markPreview(jobId, ref);

            List<MockAiJobDraftItemView> items = aiJobService.previewDraftItems(jobId);
            assertEquals(2, items.size(), "both drafted items must read back");
            MockAiJobDraftItemView first = items.get(0);
            assertEquals("订单查询桩", first.name());
            assertEquals("GET", first.method());
            assertEquals("/orders/{id}", first.path());
            assertEquals("exact", first.pathMatchType());
            assertEquals("{\"status\":200}", first.responseJson());
            assertEquals("订单查询桩", first.sourceAnchor());
            assertNull(first.scenarioName(), "absent scenario stays null, not invented");
            assertNull(first.faultJson(), "absent fault stays null");
            assertEquals("订单状态机", items.get(1).scenarioName());

            Response<List<MockAiJobDraftItemView>> echoed = controller.draftItems(jobId);
            assertEquals("0", echoed.getCode(), "internal door answers the ok envelope");
            assertEquals(2, echoed.getData().size());
        } finally {
            sweep(projectId, spaceId, jobId);
        }
    }

    /** A fault item (edge pack shape: fault key beside status) keeps its fault json verbatim. */
    @Test
    void faultItemReadsBackWithFaultJsonVerbatim() throws Exception {
        long projectId = newProject();
        long spaceId = newSpace(projectId);
        long jobId = 0;
        try {
            configCacheHolder.put(MASTER_KEY, "1");
            configCacheHolder.put(NL_STUB_KEY, "1");
            jobId = aiJobService.createJob(new MockAiJobCreateInput(spaceId, "nl-stub",
                    "支付接口限流返回429")).id();

            ObjectNode item = stubItem("支付限流剧本", "POST", "/pay", "{\"status\":429}");
            item.putObject("fault").put("type", "delay").put("minMs", 1500).put("maxMs", 3000);
            String ref = uploadBundle(jobId, bundleOf(item));

            aiJobService.markRunning(jobId);
            aiJobService.markPreview(jobId, ref);

            List<MockAiJobDraftItemView> items = aiJobService.previewDraftItems(jobId);
            assertEquals(1, items.size());
            assertTrue(items.get(0).faultJson() != null
                            && items.get(0).faultJson().contains("delay"),
                    "fault document must read back verbatim, got: " + items.get(0).faultJson());
        } finally {
            sweep(projectId, spaceId, jobId);
        }
    }

    /** A queued job has no draft yet — the read is refused in plain words, not an empty list. */
    @Test
    void queuedJobReadIsRefusedInPlainWords() {
        long projectId = newProject();
        long spaceId = newSpace(projectId);
        long jobId = 0;
        try {
            configCacheHolder.put(MASTER_KEY, "1");
            configCacheHolder.put(NL_STUB_KEY, "1");
            jobId = aiJobService.createJob(new MockAiJobCreateInput(spaceId, "nl-stub",
                    "库存接口返回503")).id();

            assertQueuedReadRefused(jobId);
        } finally {
            sweep(projectId, spaceId, jobId);
        }
    }

    private void assertQueuedReadRefused(long jobId) {
        ServiceException e = assertThrows(ServiceException.class,
                () -> aiJobService.previewDraftItems(jobId));
        assertTrue(e.getMessage().contains("预览"),
                "refusal must name the missing preview state, got: " + e.getMessage());
    }

    /** A preview whose artifact vanished is refused honestly (empty list would lie). */
    @Test
    void vanishedArtifactIsRefusedNotEmptyListed() throws Exception {
        long projectId = newProject();
        long spaceId = newSpace(projectId);
        long jobId = 0;
        try {
            configCacheHolder.put(MASTER_KEY, "1");
            configCacheHolder.put(NL_STUB_KEY, "1");
            jobId = aiJobService.createJob(new MockAiJobCreateInput(spaceId, "nl-stub",
                    "优惠券接口过期返回410")).id();
            String ref = uploadBundle(jobId, bundleOf(
                    stubItem("优惠券桩", "GET", "/coupons", "{\"status\":200}")));
            aiJobService.markRunning(jobId);
            aiJobService.markPreview(jobId, ref);
            artifactStorage.deleteQuietly(ref);
            cleanupKeys.remove(ref);

            assertMissingArtifactRefused(jobId);
        } finally {
            sweep(projectId, spaceId, jobId);
        }
    }

    private void assertMissingArtifactRefused(long jobId) {
        ServiceException e = assertThrows(ServiceException.class,
                () -> aiJobService.previewDraftItems(jobId));
        assertTrue(e.getMessage().contains("草稿产物缺失"),
                "refusal must name the missing artifact, got: " + e.getMessage());
    }

    /** A ghost job id is refused with the ledger's existing wording. */
    @Test
    void ghostJobReadIsRefused() {
        ServiceException e = assertThrows(ServiceException.class,
                () -> aiJobService.previewDraftItems(999_999_902L));
        assertTrue(e.getMessage().contains("不存在"),
                "refusal must name the missing row, got: " + e.getMessage());
    }

    private void sweep(long projectId, long spaceId, long jobId) {
        for (String key : cleanupKeys) {
            artifactStorage.deleteQuietly(key);
        }
        cleanupKeys.clear();
        configCacheHolder.remove(MASTER_KEY);
        configCacheHolder.remove(NL_STUB_KEY);
        if (jobId > 0) {
            jdbcTemplate.update("DELETE FROM tp_mock_ai_job WHERE id = ?", jobId);
        }
        jdbcTemplate.update("DELETE FROM tp_mock_space WHERE id = ?", spaceId);
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
    }
}
