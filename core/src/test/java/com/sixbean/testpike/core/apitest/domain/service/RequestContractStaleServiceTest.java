package com.sixbean.testpike.core.apitest.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.apitest.api.dto.ApiCollectionInfo;
import com.sixbean.testpike.core.apitest.api.dto.ApiRequestInfo;
import com.sixbean.testpike.core.apitest.api.dto.CollectionSaveInput;
import com.sixbean.testpike.core.apitest.api.dto.RequestSaveInput;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.spec.domain.component.OpenApiSpecParser;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = {"testpike.scheduling.enabled=false"})
class RequestContractStaleServiceTest {

    @Autowired
    private ApiRequestService requestService;
    @Autowired
    private ApiCollectionService collectionService;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;
    private long collectionId;

    /** Fixture projects swept by FK tree; never a table wipe (R37). */
    private final java.util.List<Long> fixtureProjects = new java.util.ArrayList<>();

    @BeforeEach
    void setupData() {
        String code = UUID.randomUUID().toString().substring(0, 8);
        projectId = projectService.create(1L,
                new ProjectCreateInput("徽标项目" + code, code, "zh-CN", "P1", null));
        fixtureProjects.add(projectId);
        ApiCollectionInfo collection = collectionService.create(1L,
                new CollectionSaveInput(projectId, 0L, "宠物集" + code));
        collectionId = collection.id();
    }

    @AfterEach
    void sweepFixtures() {
        SharedDbFixtureSweep.byProjectList(jdbcTemplate, fixtureProjects);
        fixtureProjects.clear();
    }

    private ApiRequestInfo createRequest(String method, String path) {
        return requestService.create(1L, new RequestSaveInput(collectionId,
                method + " " + path, method, path, "[]", "", "{}", "[]"));
    }

    
    @Test
    void diff命中已导入请求挂标未命中不动() {
        ApiRequestInfo pets = createRequest("GET", "/pets");
        ApiRequestInfo orders = createRequest("POST", "/orders");

        int marked = requestService.markContractStale(projectId, Set.of("GET /pets/?page=1"));

        assertEquals(1, marked);
        assertTrue(requestService.get(pets.id()).contractStale(), "命中键须挂标");
        assertFalse(requestService.get(orders.id()).contractStale(), "未命中键不动");
    }

    
    @Test
    void 编辑请求即清标() {
        ApiRequestInfo req = createRequest("GET", "/pets");
        requestService.markContractStale(projectId, Set.of("GET /pets"));
        assertTrue(requestService.get(req.id()).contractStale(), "前置：已挂标");

        requestService.update(1L, req.id(), new RequestSaveInput(collectionId,
                "改名后的宠物列表", "GET", "/pets", "[]", "", "{}", "[]"));

        assertFalse(requestService.get(req.id()).contractStale(), "编辑即视为已复核→清标");
    }

    
    @Test
    void 徽标只作用本项目请求() {
        createRequest("GET", "/pets");
        String code = UUID.randomUUID().toString().substring(0, 8);
        long other = projectService.create(1L,
                new ProjectCreateInput("他项目" + code, code, "zh-CN", "P1", null));
        ApiCollectionInfo otherCol = collectionService.create(1L,
                new CollectionSaveInput(other, 0L, "他集" + code));
        requestService.create(1L, new RequestSaveInput(otherCol.id(),
                "GET /pets", "GET", "/pets", "[]", "", "{}", "[]"));

        int marked = requestService.markContractStale(projectId,
                Set.of(OpenApiSpecParser.endpointKey("GET", "/pets")));

        assertEquals(1, marked, "只标本项目一条");
        List<ApiRequestInfo> otherRows = requestService.list(otherCol.id());
        assertEquals(1, otherRows.size());
        assertFalse(otherRows.get(0).contractStale());
    }

    
    @Test
    void 空键集零操作() {
        createRequest("GET", "/pets");
        assertEquals(0, requestService.markContractStale(projectId, Set.of()));
        assertEquals(0, requestService.markContractStale(projectId, null));
        assertFalse(requestService.list(collectionId).get(0).contractStale());
    }

    /**
 * C-2 acceptance spec: a hand-written request with a concrete path id must be
 * marked stale when the contract's templated endpoint changes. Before the
 * badge compared raw strings (GET /orders/20261011001 != GET /orders/{id}) and
 * silently missed such requests; the fix folds both sides to one match key
 * (numeric / UUID / long-hex / template segments collapse to {var}).
 */
    @Test
    void 手写具体路径请求对契约模板端点改版正确挂标() {
        ApiRequestInfo concrete = createRequest("GET", "/orders/20261011001");
        ApiRequestInfo uuidy = createRequest("DELETE", "/sessions/550e8400-e29b-41d4-a716-446655440000");
        ApiRequestInfo untouched = createRequest("GET", "/orders/recent");

        int marked = requestService.markContractStale(projectId,
                Set.of("GET /orders/{id}", "DELETE /sessions/{sid}"));

        assertEquals(2, marked, "具体路径与模板端点归一后同键=两条都须挂标");
        assertTrue(requestService.get(concrete.id()).contractStale());
        assertTrue(requestService.get(uuidy.id()).contractStale());
        assertFalse(requestService.get(untouched.id()).contractStale(), "字面段不被误折");
    }

    /**
     * The save chain (create and update) stores the normalized match key in the
     * first-class column contract_endpoint_key — inventory joins and change-impact
     * closures read it by equality instead of recomputing per row.
     */
    @Test
    void 保存链单点写入归一键一等列() {
        ApiRequestInfo req = createRequest("GET", "/pets/{petId}");
        String key = jdbcTemplate.queryForObject(
                "SELECT contract_endpoint_key FROM tp_apitest_request WHERE id = ?",
                String.class, req.id());
        assertEquals("GET /pets/{var}", key, "模板段折 {var} 存列");

        requestService.update(1L, req.id(), new RequestSaveInput(collectionId,
                "改挂具体路径", "GET", "/pets/42", "[]", "", "{}", "[]"));
        String updated = jdbcTemplate.queryForObject(
                "SELECT contract_endpoint_key FROM tp_apitest_request WHERE id = ?",
                String.class, req.id());
        assertEquals("GET /pets/{var}", updated, "update 链同步重算落盘");
    }
}
