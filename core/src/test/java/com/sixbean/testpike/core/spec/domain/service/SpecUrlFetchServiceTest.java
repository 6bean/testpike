package com.sixbean.testpike.core.spec.domain.service;

import com.sun.net.httpserver.HttpServer;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
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

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = {"testpike.scheduling.enabled=false"})
class SpecUrlFetchServiceTest {

    @Autowired
    private ApiSpecService apiSpecService;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ConfigCacheHolder configCache;

    private String code;
    private long projectId;
    private HttpServer server;
    private String baseUrl;
    private volatile byte[] served = new byte[0];
    private volatile int servedStatus = 200;
    private String originalAllowHosts;

    @BeforeEach
    @AfterEach
    void cleanTables() {
        SharedDbFixtureSweep.byProjectList(jdbcTemplate, SWEEP_EXTRA, fixtureProjects);
        fixtureProjects.clear();
    }

    @BeforeEach
    void startServer() throws IOException {
        originalAllowHosts = configCache.get("exec.http.allow-hosts");
        configCache.put("exec.http.allow-hosts", "");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/spec", exchange -> {
            byte[] body = served;
            exchange.sendResponseHeaders(servedStatus, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void restoreConfig() {
        server.stop(0);
        if (originalAllowHosts == null) {
            configCache.remove("exec.http.allow-hosts");
        } else {
            configCache.put("exec.http.allow-hosts", originalAllowHosts);
        }
    }

    /** Project-keyed tables without an FK into tp_project (live schema scan). */
    private static final java.util.List<String> SWEEP_EXTRA =
            java.util.List.of("tp_api_spec");

    /** Fixture projects swept by FK tree; never a table wipe (R37). */
    private final java.util.List<Long> fixtureProjects = new java.util.ArrayList<>();

    private void createProject() {
        code = UUID.randomUUID().toString().substring(0, 8);
        projectId = projectService.create(1L,
                new ProjectCreateInput("拉取项目" + code, code, "zh-CN", "P1", null));
        fixtureProjects.add(projectId);
    }

    private static final String V1 = """
            {"openapi":"3.0.1","info":{"title":"拉取API","version":"1.0"},
             "paths":{
               "/pets":{"get":{"operationId":"listPets","summary":"列表",
                   "responses":{"200":{"description":"ok"}}}},
               "/orders":{"post":{"operationId":"createOrder","responses":{"201":{"description":"c"}}}}
             }}
            """;

    
    @Test
    void URL拉取成功进同一上传链() {
        createProject();
        served = V1.getBytes(StandardCharsets.UTF_8);
        ApiSpecInfo info = apiSpecService.fetchFromUrl(1L, projectId, "pet-api", baseUrl + "/spec");
        assertNotNull(info.id());
        assertEquals(1, info.specVersion());
        assertEquals("openapi3-json", info.specFormat());
        assertEquals("拉取API", info.specTitle());
        assertEquals(2, info.endpoints().size());
        assertEquals(64, info.contentSha256().length());
    }

    
    @Test
    void 同内容重拉幂等拒() {
        createProject();
        served = V1.getBytes(StandardCharsets.UTF_8);
        apiSpecService.fetchFromUrl(1L, projectId, "pet-api", baseUrl + "/spec");
        ServiceException e = assertThrows(ServiceException.class,
                () -> apiSpecService.fetchFromUrl(1L, projectId, "pet-api", baseUrl + "/spec"));
        assertEquals(BizErrorCode.RESOURCE_CONFLICT, e.getCode());
    }

    
    @Test
    void 白名单外URL人话拒() {
        createProject();
        configCache.put("exec.http.allow-hosts", "api.example.com, *.corp.example.com");
        ServiceException e = assertThrows(ServiceException.class,
                () -> apiSpecService.fetchFromUrl(1L, projectId, "pet-api", baseUrl + "/spec"));
        assertEquals(BizErrorCode.FORBIDDEN, e.getCode());
        assertTrue(e.getMessage().contains("白名单"), "人话提示需含白名单指引: " + e.getMessage());
    }

    
    @Test
    void 白名单通配命中放行拉取() {
        createProject();
        configCache.put("exec.http.allow-hosts", "*.localhost, example.com");
        ServiceException e = assertThrows(ServiceException.class,
                () -> apiSpecService.fetchFromUrl(1L, projectId, "pet-api", baseUrl + "/spec"));
        assertEquals(BizErrorCode.FORBIDDEN, e.getCode());
        served = V1.getBytes(StandardCharsets.UTF_8);
        ApiSpecInfo info = apiSpecService.fetchFromUrl(1L, projectId, "local-api",
                "http://localhost:" + server.getAddress().getPort() + "/spec");
        assertEquals(1, info.specVersion());
    }

    
    @Test
    void 拉取非2xx人话拒() {
        createProject();
        servedStatus = 502;
        served = "bad gateway".getBytes(StandardCharsets.UTF_8);
        ServiceException e = assertThrows(ServiceException.class,
                () -> apiSpecService.fetchFromUrl(1L, projectId, "pet-api", baseUrl + "/spec"));
        assertEquals(BizErrorCode.VALIDATION_ERROR, e.getCode());
        assertTrue(e.getMessage().contains("502"), "人话提示需含上游状态码: " + e.getMessage());
    }

    
    @Test
    void 非OpenAPI内容人话拒() {
        createProject();
        served = "<html><body>not a spec</body></html>".getBytes(StandardCharsets.UTF_8);
        ServiceException e = assertThrows(ServiceException.class,
                () -> apiSpecService.fetchFromUrl(1L, projectId, "pet-api", baseUrl + "/spec"));
        assertEquals(BizErrorCode.VALIDATION_ERROR, e.getCode());
    }

 
    @Test
    void 非http协议人话拒() {
        createProject();
        ServiceException e = assertThrows(ServiceException.class,
                () -> apiSpecService.fetchFromUrl(1L, projectId, "pet-api", "file:///etc/passwd"));
        assertEquals(BizErrorCode.VALIDATION_ERROR, e.getCode());
        assertTrue(e.getMessage().contains("http"), "人话提示需说明仅支持 http/https: " + e.getMessage());
    }
}
