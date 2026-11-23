package com.sixbean.testpike.core.spec.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.spec.api.dto.ApiSpecInfo;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = {"testpike.scheduling.enabled=false"})
class ApiSpecServiceTest {

    @Autowired
    private ApiSpecService apiSpecService;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String code;
    private long projectId;

    @BeforeEach
    @AfterEach
    void cleanTables() {
        SharedDbFixtureSweep.byProjectList(jdbcTemplate, SWEEP_EXTRA, fixtureProjects);
        fixtureProjects.clear();
    }

    /** Project-keyed tables without an FK into tp_project (live schema scan). */
    private static final java.util.List<String> SWEEP_EXTRA =
            java.util.List.of("tp_api_spec");

    /** Fixture projects swept by FK tree; never a table wipe (R37). */
    private final java.util.List<Long> fixtureProjects = new java.util.ArrayList<>();

    private void createProject() {
        code = UUID.randomUUID().toString().substring(0, 8);
        projectId = projectService.create(1L,
                new ProjectCreateInput("契约项目" + code, code, "zh-CN", "P1", null));
        fixtureProjects.add(projectId);
    }

    private static byte[] json(String body) {
        return body.getBytes(StandardCharsets.UTF_8);
    }

    private static final String V1 = """
            {"openapi":"3.0.1","info":{"title":"契约API","version":"1.0"},
             "paths":{
               "/pets":{"get":{"operationId":"listPets","summary":"列表",
                   "parameters":[{"name":"limit","in":"query","schema":{"type":"integer"}}],
                   "responses":{"200":{"description":"ok"}}},
                 "post":{"operationId":"createPet","responses":{"201":{"description":"c"}}}},
               "/pets/{id}":{"delete":{"operationId":"del","responses":{"204":{"description":"g"}}}},
               "/extra":{"get":{"operationId":"x","responses":{"200":{"description":"ok"}}}}
             }}
            """;

    
    private static final String V2 = """
            {"openapi":"3.0.1","info":{"title":"契约API","version":"1.1"},
             "paths":{
               "/pets":{"get":{"operationId":"listPets","summary":"列表",
                   "parameters":[{"name":"limit","in":"query","required":true,
                                  "schema":{"type":"integer"}},
                                 {"name":"page","in":"query","schema":{"type":"integer"}}],
                   "responses":{"200":{"description":"ok"}}},
                 "patch":{"operationId":"patchPet","responses":{"200":{"description":"ok"}}}}
             }}
            """;

    
    private static final String V4 = """
            {"openapi":"3.0.1","info":{"title":"仓储API","version":"1.0"},
             "paths":{"/warehouse":{"get":{"operationId":"listWh","responses":{"200":{"description":"ok"}}}}}}
            """;

    @Test
    void 上传v1落库版本1含清单() {
        createProject();
        ApiSpecInfo v1 = apiSpecService.upload(1L, projectId, "pet-api",
                json(V1), null);
        assertNotNull(v1.id());
        assertEquals(1, v1.specVersion());
        assertEquals("openapi3-json", v1.specFormat());
        assertEquals("契约API", v1.specTitle());
        assertEquals("pet-api", v1.name());
        assertNull(v1.prevSpecId());
        assertEquals(4, v1.endpoints().size());
        assertEquals(64, v1.contentSha256().length());
        assertNotNull(v1.createdAt());
    }

    @Test
    void 同内容重传幂等拒() {
        createProject();
        apiSpecService.upload(1L, projectId, "pet-api", json(V1), null);
        ServiceException e = assertThrows(ServiceException.class,
                () -> apiSpecService.upload(1L, projectId, "pet-api", json(V1), null));
        assertEquals(BizErrorCode.RESOURCE_CONFLICT, e.getCode());
        assertTrue(e.getMessage().contains("一致"));
    }

    @Test
    void 改结构重传版本链与diff三分类() {
        createProject();
        ApiSpecInfo v1 = apiSpecService.upload(1L, projectId, "pet-api", json(V1), null);
        ApiSpecInfo v2 = apiSpecService.upload(1L, projectId, "pet-api", json(V2), null);
        assertEquals(2, v2.specVersion());
        assertEquals(v1.id(), v2.prevSpecId());
        assertEquals(List.of("PATCH /pets"), v2.diffSummary().added());
        assertEquals(List.of("DELETE /pets/{id}", "GET /extra", "POST /pets"),
                v2.diffSummary().removed());
        assertEquals(List.of("GET /pets"), v2.diffSummary().changed());
    }

    @Test
    void 版本历史升序链() {
        createProject();
        ApiSpecInfo v1 = apiSpecService.upload(1L, projectId, "pet-api", json(V1), null);
        ApiSpecInfo v2 = apiSpecService.upload(1L, projectId, "pet-api", json(V2), null);
        ApiSpecInfo other = apiSpecService.upload(1L, projectId, "other-api", json(V4), null);
        List<ApiSpecInfo> chain = apiSpecService.history(projectId, "pet-api");
        assertEquals(2, chain.size());
        assertEquals(v1.id(), chain.get(0).id());
        assertEquals(v2.id(), chain.get(1).id());
        assertEquals("other-api", other.name());
    }

    
    @Test
    void 跨名同端点上传被重复定义门拒() {
        createProject();
        ApiSpecInfo v1 = apiSpecService.upload(1L, projectId, "pet-api", json(V1), null);
        ServiceException e = assertThrows(ServiceException.class,
                () -> apiSpecService.upload(1L, projectId, "mirror-api", json(V1), null));
        assertEquals(BizErrorCode.VALIDATION_ERROR, e.getCode());
        assertTrue(e.getMessage().contains("已由契约"), e.getMessage());
        assertTrue(e.getMessage().contains("pet-api"), e.getMessage());
        assertTrue(e.getMessage().contains(String.valueOf(v1.id())), e.getMessage());
    }

    @Test
    void 超五兆人话拒() {
        createProject();
        byte[] big = new byte[5 * 1024 * 1024 + 16];
        ServiceException e = assertThrows(ServiceException.class,
                () -> apiSpecService.upload(1L, projectId, "big-api", big, null));
        assertTrue(e.getMessage().contains("5MB"));
    }

    @Test
    void 项目清单分页与详情查询() {
        createProject();
        ApiSpecInfo v1 = apiSpecService.upload(1L, projectId, "pet-api", json(V1), null);
        var page = apiSpecService.list(projectId, 1, 10);
        assertEquals(1, page.list().size());
        assertEquals(1L, page.total());
        assertEquals(v1.id(), page.list().get(0).id());
        ApiSpecInfo got = apiSpecService.get(v1.id());
        assertEquals(4, got.endpoints().size());
    }

    @Test
    void 详情不存在id_404人话() {
        createProject();
        ServiceException e = assertThrows(ServiceException.class,
                () -> apiSpecService.get(999999999L));
        assertEquals(BizErrorCode.NOT_FOUND, e.getCode());
        assertTrue(e.getMessage().contains("契约版本不存在"));
    }
}
