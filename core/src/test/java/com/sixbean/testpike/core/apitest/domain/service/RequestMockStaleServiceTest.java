package com.sixbean.testpike.core.apitest.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.apitest.api.dto.ApiCollectionInfo;
import com.sixbean.testpike.core.apitest.api.dto.ApiRequestInfo;
import com.sixbean.testpike.core.apitest.api.dto.CollectionSaveInput;
import com.sixbean.testpike.core.apitest.api.dto.RequestSaveInput;
import com.sixbean.testpike.core.mock.api.dto.MockSpaceInput;
import com.sixbean.testpike.core.mock.api.dto.MockStubInput;
import com.sixbean.testpike.core.mock.domain.service.MockSpaceService;
import com.sixbean.testpike.core.mock.domain.service.MockStubService;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.spec.domain.service.ApiSpecService;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * the mock_stale badge chain (AG M3 contract-direct exit).
 * <p>Semantics copied from the W1b twin (contract_stale): when a new contract
 * version diff marks endpoints changed/removed, the requests in that project
 * whose endpoint key also has a derived mock stub (source=openapi rule in the
 * project's spaces) get mock_stale=true; a changed endpoint WITHOUT a derived
 * stub only gets contract_stale (twin scope preserved, never widened);
 * editing the request = human review = both badges clear at the save-chain
 * single point. Judgment source stays SpecDiff — no second diff spec.
 * <p>Runs against the real dev database (SpringBootTest, zero mocks), same
 * posture as RequestContractStaleServiceTest.
 */
@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = {"testpike.scheduling.enabled=false"})
class RequestMockStaleServiceTest {

    @Autowired
    private ApiRequestService requestService;
    @Autowired
    private ApiCollectionService collectionService;
    @Autowired
    private ApiSpecService specService;
    @Autowired
    private MockSpaceService spaceService;
    @Autowired
    private MockStubService stubService;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;
    private long collectionId;
    private String specName;

    private static final String V1 = """
            {"openapi":"3.0.1","info":{"title":"徽标API","version":"1.0"},
             "paths":{
               "/pets":{"get":{"operationId":"listPets",
                   "parameters":[{"name":"limit","in":"query","schema":{"type":"integer"}}],
                   "responses":{"200":{"description":"ok"}}},
                 "post":{"operationId":"createPet","responses":{"201":{"description":"c"}}}},
               "/pets/{id}":{"delete":{"operationId":"del","responses":{"204":{"description":"g"}}}}
             }}
            """;

    /** v2: GET /pets gains a param (changed); DELETE /pets/{id} disappears (removed). */
    private static final String V2 = """
            {"openapi":"3.0.1","info":{"title":"徽标API","version":"1.1"},
             "paths":{
               "/pets":{"get":{"operationId":"listPets",
                   "parameters":[{"name":"limit","in":"query","schema":{"type":"integer"}},
                                 {"name":"page","in":"query","schema":{"type":"integer"}}],
                   "responses":{"200":{"description":"ok"}}},
                 "post":{"operationId":"createPet","responses":{"201":{"description":"c"}}}}
             }}
            """;

    /** Project-keyed tables without an FK into tp_project (live schema scan). */
    private static final java.util.List<String> SWEEP_EXTRA =
            java.util.List.of("tp_api_spec", "tp_mock_space");

    /** Fixture projects swept by FK tree; never a table wipe (R37). */
    private final java.util.List<Long> fixtureProjects = new java.util.ArrayList<>();

    @BeforeEach
    void setupData() {
        String code = UUID.randomUUID().toString().substring(0, 8);
        projectId = projectService.create(1L,
                new ProjectCreateInput("Mock徽标项目" + code, code, "zh-CN", "P1", null));
        fixtureProjects.add(projectId);
        ApiCollectionInfo collection = collectionService.create(1L,
                new CollectionSaveInput(projectId, 0L, "宠物集" + code));
        collectionId = collection.id();
        specName = "pet-api-" + code;
    }

    @AfterEach
    void sweepFixtures() {
        SharedDbFixtureSweep.byProjectList(jdbcTemplate, SWEEP_EXTRA, fixtureProjects);
        fixtureProjects.clear();
    }

    private ApiRequestInfo createRequest(String method, String path) {
        return requestService.create(1L, new RequestSaveInput(collectionId,
                method + " " + path, method, path, "[]", "", "{}", "[]"));
    }

    private long newSpace() {
        String key = "mst" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        return spaceService.create(projectId, new MockSpaceInput("Space " + key, key, null, null,
                "prefix", "none", null, null, 100, null));
    }

    private void createDerivedStub(long spaceId, String method, String pathPattern) {
        stubService.create(spaceId, new MockStubInput("stub " + pathPattern, 100, method,
                pathPattern.indexOf('{') >= 0 ? "template" : "exact", pathPattern,
                "[]", "{}", null, null, null, "openapi", "spec:preview", true));
    }

    private void uploadChain() {
        specService.upload(1L, projectId, specName, V1.getBytes(StandardCharsets.UTF_8), null);
        specService.upload(1L, projectId, specName, V2.getBytes(StandardCharsets.UTF_8), null);
    }

    /** Diff hit + derived stub exists → mock_stale; diff hit without stub → twin scope only. */
    @Test
    void 契约改版命中来源stub挂标无stub仅契约标() {
        ApiRequestInfo pets = createRequest("GET", "/pets");
        createRequest("DELETE", "/pets/{id}");
        ApiRequestInfo created = createRequest("POST", "/pets");
        long spaceId = newSpace();
        createDerivedStub(spaceId, "GET", "/pets");

        uploadChain();

        assertTrue(requestService.get(pets.id()).contractStale(), "changed 端点先走孪生契约标");
        assertTrue(requestService.get(pets.id()).mockStale(),
                "该端点存在 openapi 来源 stub → mock_stale 挂标");
        ApiRequestInfo removedEndpoint = requestService.list(collectionId).stream()
                .filter(r -> "DELETE".equals(r.method())).findFirst().orElseThrow();
        assertTrue(removedEndpoint.contractStale(), "removed 端点契约标在位");
        assertFalse(removedEndpoint.mockStale(), "removed 端点无来源 stub → mock_stale 不扩权");
        assertFalse(requestService.get(created.id()).contractStale(), "未命中键不动");
        assertFalse(requestService.get(created.id()).mockStale());
    }

    /** Editing the request is the human-review action: both badges clear at the save chain. */
    @Test
    void 编辑请求即清孪生标() {
        ApiRequestInfo pets = createRequest("GET", "/pets");
        long spaceId = newSpace();
        createDerivedStub(spaceId, "GET", "/pets");
        uploadChain();
        assertTrue(requestService.get(pets.id()).mockStale(), "前置：已挂标");

        requestService.update(1L, pets.id(), new RequestSaveInput(collectionId,
                "复核后的宠物列表", "GET", "/pets", "[]", "", "{}", "[]"));

        assertFalse(requestService.get(pets.id()).mockStale(), "编辑即复核→清 mock_stale");
        assertFalse(requestService.get(pets.id()).contractStale(), "孪生契约标同点清");
    }

    /** No mock space in the project → contract diff must leave mock_stale false (subset scope). */
    @Test
    void 无派生stub时徽标止于契约谱() {
        ApiRequestInfo pets = createRequest("GET", "/pets");

        uploadChain();

        assertTrue(requestService.get(pets.id()).contractStale(), "契约标照常");
        assertFalse(requestService.get(pets.id()).mockStale(), "无 stub→mock 标零挂");
    }

    /** Isolation preserved: another project's request/stub pair is never touched. */
    @Test
    void 跨项目不误伤() {
        ApiRequestInfo pets = createRequest("GET", "/pets");
        long spaceId = newSpace();
        createDerivedStub(spaceId, "GET", "/pets");

        String code = UUID.randomUUID().toString().substring(0, 8);
        long other = projectService.create(1L,
                new ProjectCreateInput("他项目" + code, code, "zh-CN", "P1", null));
        ApiCollectionInfo otherCol = collectionService.create(1L,
                new CollectionSaveInput(other, 0L, "他集" + code));
        long otherSpace = spaceService.create(other, new MockSpaceInput("Other " + code,
                "other" + code.substring(0, 6), null, null, "prefix", "none", null, null, 100, null));
        createDerivedStub(otherSpace, "GET", "/pets");
        requestService.create(1L, new RequestSaveInput(otherCol.id(),
                "GET /pets", "GET", "/pets", "[]", "", "{}", "[]"));

        uploadChain();

        assertTrue(requestService.get(pets.id()).mockStale(), "本项目命中挂标");
        assertTrue(requestService.list(otherCol.id()).stream()
                .noneMatch(r -> r.mockStale()), "他项目同名端点零误伤");
    }
}
