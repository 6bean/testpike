package com.sixbean.testpike.core.mock.domain.service;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import com.sixbean.testpike.core.executor.domain.component.ArtifactStorage;
import com.sixbean.testpike.core.mock.api.controller.MockAiJobInternalController;
import com.sixbean.testpike.core.mock.api.dto.MockAiJobCreateInput;
import com.sixbean.testpike.core.mock.api.dto.MockAiJobInfo;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Artifact grant door of AI drafting jobs :
 * the preview bundle a model produces far exceeds the 300-char cap of
 * result_preview_ref, so the consumer must store the bundle in object storage
 * and write back only the reference. This door issues that presigned PUT
 * grant (ExploreInternalController evidence-grant precedent — project-routed
 * storage, single-point key building).
 * <p>Contract under test: a live job (any status the row exists in) gets a
 * grant whose key follows {prefix}/{projectId}/mock-draft/yyyyMM/... and
 * whose url is a real presigned PUT; a ghost job is refused in plain words;
 * the bytes PUT through the grant are confirmed present via the same
 * read/write-same-route existence check.
 * <p>Real dev database + real S3, zero mocks (MockAiJobServiceTest
 * posture); fixtures cleaned in finally with zero residue.
 */
@SpringBootTest(classes = CoreApplication.class)
class MockAiJobArtifactGrantTest {

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

    private final List<String> cleanupKeys = new ArrayList<>();

    private long newProject() {
        String code = "t365g" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        return jdbcTemplate.queryForObject(
                "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1) RETURNING id",
                Long.class, code, code);
    }

    private long newSpace(long projectId) {
        String key = "t365g-" + UUID.randomUUID().toString().substring(0, 10);
        return spaceService.create(projectId,
                new MockSpaceInput("Space " + key, key, null, null,
                        "prefix", "none", null, null, 100, null));
    }

    /** Live job → grant key under the mock-draft scene, presigned PUT round-trips real bytes. */
    @Test
    void grantForLiveJobIssuesMockDraftKeyAndBytesRoundTripThroughS3() throws Exception {
        long projectId = newProject();
        long spaceId = newSpace(projectId);
        long jobId = 0;
        try {
            configCacheHolder.put(MASTER_KEY, "1");
            configCacheHolder.put(NL_STUB_KEY, "1");
            jobId = aiJobService.createJob(new MockAiJobCreateInput(spaceId, "nl-stub",
                    "订单接口超时返回5001")).id();

            MockDraftArtifactGrantView grant = aiJobService.artifactGrant(jobId, "draft.json");
            assertNotNull(grant.objectKey(), "grant must carry the canonical object key");
            cleanupKeys.add(grant.objectKey());
            assertTrue(grant.objectKey().contains("/" + projectId + "/mock-draft/"),
                    "key must be project-routed under the mock-draft scene, got: " + grant.objectKey());
            assertTrue(grant.uploadUrl().startsWith("http://"),
                    "grant must carry a real presigned PUT url, got: " + grant.uploadUrl());
            assertEquals(jobId, grant.jobId());

            byte[] payload = ("{\"stubs\":[" + "x".repeat(400) + "]}").getBytes(StandardCharsets.UTF_8);
            HttpResponse<Void> put = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(grant.uploadUrl()))
                            .PUT(HttpRequest.BodyPublishers.ofByteArray(payload))
                            .header("Content-Type", "application/json")
                            .build(),
                    HttpResponse.BodyHandlers.discarding());
            assertEquals(200, put.statusCode(), "presigned PUT must accept the draft bundle");
            assertTrue(artifactStorage.exists(grant.objectKey()),
                    "written bundle must be visible through the same read/write route");
        } finally {
            for (String key : cleanupKeys) {
                artifactStorage.deleteQuietly(key);
            }
            configCacheHolder.remove(MASTER_KEY);
            configCacheHolder.remove(NL_STUB_KEY);
            jdbcTemplate.update("DELETE FROM tp_mock_ai_job WHERE space_id = ?", spaceId);
            jdbcTemplate.update("DELETE FROM tp_mock_space WHERE id = ?", spaceId);
            jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
        }
    }

    /** A ghost job id is refused in plain words — no grant for a row that never existed. */
    @Test
    void grantForGhostJobIsRefused() {
        ServiceException e = assertThrows(ServiceException.class,
                () -> aiJobService.artifactGrant(999_999_901L, "draft.json"));
        assertTrue(e.getMessage().contains("不存在"),
                "refusal must name the missing row, got: " + e.getMessage());
    }

    /** The internal door answers the grant with the ok envelope the consumer parses. */
    @Test
    void controllerDoorEchoesGrantForLiveJob() {
        long projectId = newProject();
        long spaceId = newSpace(projectId);
        long jobId = 0;
        try {
            configCacheHolder.put(MASTER_KEY, "1");
            configCacheHolder.put(NL_STUB_KEY, "1");
            jobId = aiJobService.createJob(new MockAiJobCreateInput(spaceId, "nl-stub",
                    "查询接口空结果返回2041")).id();

            Response<MockDraftArtifactGrantView> response = controller.grantArtifact(jobId, "draft.json");
            assertEquals("0", response.getCode());
            MockDraftArtifactGrantView grant = response.getData();
            cleanupKeys.add(grant.objectKey());
            assertEquals(jobId, grant.jobId());
            assertTrue(grant.objectKey().contains("/mock-draft/"));
        } finally {
            for (String key : cleanupKeys) {
                artifactStorage.deleteQuietly(key);
            }
            configCacheHolder.remove(MASTER_KEY);
            configCacheHolder.remove(NL_STUB_KEY);
            jdbcTemplate.update("DELETE FROM tp_mock_ai_job WHERE space_id = ?", spaceId);
            jdbcTemplate.update("DELETE FROM tp_mock_space WHERE id = ?", spaceId);
            jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
        }
    }
}
