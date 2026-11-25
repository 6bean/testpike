package com.sixbean.testpike.core.spec.domain.service;

import com.sun.net.httpserver.HttpServer;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.spec.api.dto.SpecSubscriptionInfo;
import com.sixbean.testpike.core.spec.api.dto.SpecSubscriptionSaveRequest;
import com.sixbean.testpike.core.spec.api.dto.SubscriptionScanResult;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = {"testpike.scheduling.enabled=false"})
class SpecSubscriptionServiceTest {

    @Autowired
    private SpecSubscriptionService subscriptionService;
    @Autowired
    private ApiSpecService apiSpecService;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ConfigCacheHolder configCache;

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
        String code = UUID.randomUUID().toString().substring(0, 8);
        projectId = projectService.create(1L,
                new ProjectCreateInput("订阅项目" + code, code, "zh-CN", "P1", null));
        fixtureProjects.add(projectId);
    }

    private static final String V1 = """
            {"openapi":"3.0.1","info":{"title":"订阅API","version":"1.0"},
             "paths":{
               "/pets":{"get":{"operationId":"listPets","responses":{"200":{"description":"ok"}}}}
             }}
            """;

    
    private static final String V2 = """
            {"openapi":"3.0.1","info":{"title":"订阅API","version":"1.0"},
             "paths":{
               "/pets":{"get":{"operationId":"listPets","responses":{"200":{"description":"ok"}}}},
               "/orders":{"post":{"operationId":"createOrder","responses":{"201":{"description":"c"}}}}
             }}
            """;

    private SpecSubscriptionInfo createSubscription() {
        createProject();
        return subscriptionService.create(1L, projectId, "pet-api", baseUrl + "/spec", 60);
    }

    
    private void forceDue(long subscriptionId) {
        jdbcTemplate.update("UPDATE tp_spec_subscription SET last_check_at = now() - interval '2 hours' WHERE id = ?",
                subscriptionId);
    }

    
    @Test
    void 建订阅落库且同名契约名409人话() {
        SpecSubscriptionInfo info = createSubscription();
        assertNotNull(info.id());
        assertEquals(projectId, info.projectId());
        assertEquals("pet-api", info.name());
        assertEquals(60, info.checkIntervalMinutes());
        assertTrue(info.enabled());
        assertNull(info.lastStatus(), "新建未扫=无结局");

        ServiceException dup = assertThrows(ServiceException.class,
                () -> subscriptionService.create(1L, projectId, "pet-api", baseUrl + "/other", 60));
        assertEquals(BizErrorCode.RESOURCE_CONFLICT, dup.getCode());
        assertTrue(dup.getMessage().contains("pet-api"), "人话必须带冲突订阅名: " + dup.getMessage());
    }

    
    @Test
    void 非http地址建订阅400人话() {
        createProject();
        ServiceException ex = assertThrows(ServiceException.class,
                () -> subscriptionService.create(1L, projectId, "坏源", "file:///etc/passwd", 60));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("http"), "人话须说明只支持 http/https: " + ex.getMessage());
    }

    
    @Test
    void 首扫产v1零打扰() {
        SpecSubscriptionInfo sub = createSubscription();
        served = V1.getBytes(StandardCharsets.UTF_8);

        SubscriptionScanResult r = subscriptionService.scanDue();
        assertEquals(1, r.scanned());
        assertEquals(1, r.updated());
        assertEquals(0, r.failed());
        Long specCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_api_spec WHERE project_id = ? AND name = 'pet-api'",
                Long.class, projectId);
        assertEquals(1L, specCount);
        Long notifCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_notification WHERE event_type = 'SPEC_CHANGE_DETECTED'", Long.class);
        assertEquals(0L, notifCount, "首版无 diff=不产变更通知（防建订阅即刷屏）");
        Long previewCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_api_spec_preview WHERE spec_id IS NOT NULL", Long.class);
        assertEquals(0L, previewCount, "首版无 diff=不产预览待办");
    }

    
    @Test
    void 变更再扫产新版本预览与站内信() {
        SpecSubscriptionInfo sub = createSubscription();
        served = V1.getBytes(StandardCharsets.UTF_8);
        subscriptionService.scanDue();

        served = V2.getBytes(StandardCharsets.UTF_8);
        forceDue(sub.id());
        SubscriptionScanResult r = subscriptionService.scanDue();
        assertEquals(1, r.updated());

        Integer version = jdbcTemplate.queryForObject(
                "SELECT spec_version FROM tp_api_spec WHERE project_id = ? AND name = 'pet-api'"
                        + " ORDER BY spec_version DESC LIMIT 1", Integer.class, projectId);
        assertEquals(2, version, "变更轮应挂版本链 v2");

        Long previewTodo = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_api_spec_preview p JOIN tp_api_spec s ON p.spec_id = s.id"
                        + " WHERE s.name = 'pet-api' AND s.spec_version = 2", Long.class);
        assertTrue(previewTodo != null && previewTodo >= 1, "变更版本须产预览待办（订阅只产 pending 不自动写入）");

        Long notified = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_notification WHERE event_type = 'SPEC_CHANGE_DETECTED'"
                        + " AND project_id = ?", Long.class, projectId);
        assertTrue(notified != null && notified >= 1,
                "项目负责人∪创建人须收到契约变更站内信（创建人=1 至少 1 条）");
    }

    
    @Test
    void 内容一致转unchanged零通知() {
        SpecSubscriptionInfo sub = createSubscription();
        served = V1.getBytes(StandardCharsets.UTF_8);
        subscriptionService.scanDue();

        forceDue(sub.id());
        SubscriptionScanResult r = subscriptionService.scanDue();
        assertEquals(1, r.unchanged());
        assertEquals(0, r.updated());
        // Scope to this run's project: other suites legitimately insert rows into
        // tp_api_spec inside the same JVM, a global count is not run-owned (R37).
        Long specCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_api_spec WHERE project_id = ? AND name = 'pet-api'",
                Long.class, projectId);
        assertEquals(1L, specCount);
        Long notifCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_notification WHERE event_type = 'SPEC_CHANGE_DETECTED'", Long.class);
        assertEquals(0L, notifCount);
        String status = jdbcTemplate.queryForObject(
                "SELECT last_status FROM tp_spec_subscription WHERE id = ?", String.class, sub.id());
        assertEquals("unchanged", status);
    }

    
    @Test
    void 上游挂failed记错误不炸循环() {
        SpecSubscriptionInfo broken = createSubscription();
        servedStatus = 500;
        forceDue(broken.id());

        SubscriptionScanResult r = subscriptionService.scanDue();
        assertEquals(1, r.scanned());
        assertEquals(1, r.failed());
        assertEquals(0, r.updated());
        String err = jdbcTemplate.queryForObject(
                "SELECT last_error FROM tp_spec_subscription WHERE id = ?", String.class, broken.id());
        assertNotNull(err, "failed 必须记 last_error");
        assertTrue(err.length() <= 500);

        servedStatus = 200;
        served = V1.getBytes(StandardCharsets.UTF_8);
        forceDue(broken.id());
        SubscriptionScanResult r2 = subscriptionService.scanDue();
        assertEquals(1, r2.updated());
        assertNull(jdbcTemplate.queryForObject(
                "SELECT last_error FROM tp_spec_subscription WHERE id = ?", String.class, broken.id()),
                "成功轮须清 last_error");
    }

    
    @Test
    void 编辑订阅改字段换址即扫重名409不存在404() {
        SpecSubscriptionInfo sub = createSubscription();
        served = V1.getBytes(StandardCharsets.UTF_8);
        subscriptionService.scanDue();

        SpecSubscriptionInfo updated = subscriptionService.update(1L, sub.id(),
                "pet-api-v2", baseUrl + "/spec2", 30, null);
        assertEquals("pet-api-v2", updated.name());
        assertEquals(30, updated.checkIntervalMinutes());
        assertNull(updated.lastCheckAt(), "换 URL 须重置到期=下轮立扫（新址不该再等旧间隔窗口）");
        assertTrue(updated.enabled(), "enabled 传空=保持原值");

        SubscriptionScanResult r = subscriptionService.scanDue();
        assertEquals(1, r.scanned(), "换址后下轮即扫");

        subscriptionService.create(1L, projectId, "occupied", baseUrl + "/spec", 60);
        long firstId = sub.id();
        subscriptionService.update(1L, firstId, "n1", baseUrl + "/spec", 60, null);
        ServiceException dup = assertThrows(ServiceException.class,
                () -> subscriptionService.update(1L, firstId, "occupied", baseUrl + "/spec", 60, null));
        assertEquals(BizErrorCode.RESOURCE_CONFLICT, dup.getCode());
        assertTrue(dup.getMessage().contains("occupied"), "人话须带冲突名: " + dup.getMessage());
        SpecSubscriptionInfo same = subscriptionService.update(1L, firstId,
                "n1", baseUrl + "/spec", 60, null);
        assertEquals("n1", same.name());

        ServiceException nf = assertThrows(ServiceException.class,
                () -> subscriptionService.update(1L, 999_999L, "x", baseUrl + "/spec", 60, null));
        assertEquals(BizErrorCode.NOT_FOUND, nf.getCode());
    }

    
    @Test
    void 停用跳过启用恢复() {
        SpecSubscriptionInfo sub = createSubscription();
        served = V1.getBytes(StandardCharsets.UTF_8);
        subscriptionService.scanDue();

        subscriptionService.update(1L, sub.id(), "pet-api", baseUrl + "/spec", 60, false);
        forceDue(sub.id());
        SubscriptionScanResult paused = subscriptionService.scanDue();
        assertEquals(0, paused.scanned(), "停用订阅不参与扫描");

        subscriptionService.update(1L, sub.id(), "pet-api", baseUrl + "/spec", 60, true);
        SubscriptionScanResult back = subscriptionService.scanDue();
        assertEquals(1, back.scanned(), "重新启用即恢复扫描");
    }

    
    @Test
    void 删除订阅后不扫重复删404() {
        SpecSubscriptionInfo sub = createSubscription();
        served = V1.getBytes(StandardCharsets.UTF_8);
        subscriptionService.scanDue();

        subscriptionService.delete(1L, sub.id());
        Long rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_spec_subscription WHERE id = ?", Long.class, sub.id());
        assertEquals(0L, rows);

        forceDue(sub.id());
        SubscriptionScanResult r = subscriptionService.scanDue();
        assertEquals(0, r.scanned(), "删除后零扫描");

        ServiceException nf = assertThrows(ServiceException.class,
                () -> subscriptionService.delete(1L, sub.id()));
        assertEquals(BizErrorCode.NOT_FOUND, nf.getCode());
    }

    
    @Test
    void 列表按项目分页不串项目() {
        createSubscription();
        long firstProject = projectId;
        createProject();
        long secondProject = projectId;
        subscriptionService.create(1L, secondProject, "订单契约", baseUrl + "/spec", 60);
        subscriptionService.create(1L, secondProject, "库存契约", baseUrl + "/spec", 60);

        var p1 = subscriptionService.list(firstProject, 1, 20);
        assertEquals(1, p1.total(), "跨项目不串");
        assertEquals("pet-api", p1.list().get(0).name());

        var p2 = subscriptionService.list(secondProject, 1, 1);
        assertEquals(2, p2.total());
        assertEquals(1, p2.list().size(), "分页 size=1 只回一行");
        assertEquals("库存契约", p2.list().get(0).name());
    }

    
    @Test
    void 未到期不拉() {
        createSubscription();
        served = V1.getBytes(StandardCharsets.UTF_8);

        SubscriptionScanResult first = subscriptionService.scanDue();
        assertEquals(1, first.scanned(), "新建=立即可扫");

        SubscriptionScanResult again = subscriptionService.scanDue();
        assertEquals(0, again.scanned(), "间隔窗口内不重复拉");
        // Project-scoped count: foreign tp_api_spec rows from sibling suites in the
        // same JVM must not inflate this assertion (R37 shared-db fixture design).
        Long specCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_api_spec WHERE project_id = ? AND name = 'pet-api'",
                Long.class, projectId);
        assertEquals(1L, specCount, "第二次扫零 fetch 零新版本");
    }

    // ---------- set-internal contract subscription (program_spec source) ----------

    private final List<Long> createdProgramIds = new ArrayList<>();

    /** Program fixtures are self-created and deleted by id (R37: never TRUNCATE factory tables). */
    @AfterEach
    void cleanProgramFixtures() {
        for (long programId : createdProgramIds) {
            jdbcTemplate.update("DELETE FROM tp_program_project WHERE program_id = ?", programId);
            jdbcTemplate.update("DELETE FROM tp_program WHERE id = ?", programId);
        }
        createdProgramIds.clear();
    }

    private long createProgram(String suffix) {
        String name = "Y链集" + suffix + UUID.randomUUID().toString().substring(0, 8);
        jdbcTemplate.update("INSERT INTO tp_program (name, created_by) VALUES (?, 1)", name);
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_program WHERE name = ?", Long.class, name);
        createdProgramIds.add(id);
        return id;
    }

    private void joinProgram(long programId, long memberId) {
        jdbcTemplate.update(
                "INSERT INTO tp_program_project (program_id, project_id, created_by) VALUES (?, ?, 1)",
                programId, memberId);
    }

    private SpecSubscriptionInfo programSubscription(long consumer, String subName,
                                                     long providerProject, String providerSpec) {
        return subscriptionService.create(1L, new SpecSubscriptionSaveRequest(
                consumer, subName, null, 60, null, "program_spec", providerProject, providerSpec));
    }

    
    @Test
    void 集内订阅建订阅四闸人话() {
        createProject();
        long consumer = projectId;
        createProject();
        long provider = projectId;
        long program = createProgram("gate");
        joinProgram(program, consumer);
        joinProgram(program, provider);

        ServiceException badKind = assertThrows(ServiceException.class,
                () -> subscriptionService.create(1L, new SpecSubscriptionSaveRequest(
                        consumer, "坏源型", null, 60, null, "ftp_spec", provider, "共享契约")));
        assertEquals(BizErrorCode.VALIDATION_ERROR, badKind.getCode());
        assertTrue(badKind.getMessage().contains("program_spec"),
                "人话须点名允许的来源型: " + badKind.getMessage());

        ServiceException noPair = assertThrows(ServiceException.class,
                () -> subscriptionService.create(1L, new SpecSubscriptionSaveRequest(
                        consumer, "缺提供方", null, 60, null, "program_spec", null, null)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, noPair.getCode());

        ServiceException ghost = assertThrows(ServiceException.class,
                () -> programSubscription(consumer, "幽灵订阅", provider, "不存在契约"));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ghost.getCode());
        assertTrue(ghost.getMessage().contains("不存在契约"),
                "人话须点名缺失的契约名: " + ghost.getMessage());

        String outCode = UUID.randomUUID().toString().substring(0, 8);
        long outsider = projectService.create(1L, new ProjectCreateInput(
                "外围项目" + outCode, outCode, "zh-CN", "P1", null));
        fixtureProjects.add(outsider);
        ServiceException fence = assertThrows(ServiceException.class,
                () -> programSubscription(consumer, "跨集订阅", outsider, "共享契约"));
        assertEquals(BizErrorCode.VALIDATION_ERROR, fence.getCode());
        assertTrue(fence.getMessage().contains("项目集"),
                "护栏人话须点名项目集: " + fence.getMessage());
    }

    
    @Test
    void 集内订阅改版再扫产新版本站内信() {
        createProject();
        long consumer = projectId;
        createProject();
        long provider = projectId;
        long program = createProgram("pull");
        joinProgram(program, consumer);
        joinProgram(program, provider);
        apiSpecService.upload(1L, provider, "共享契约", V1.getBytes(StandardCharsets.UTF_8), null);

        SpecSubscriptionInfo sub = programSubscription(consumer, "shared-api", provider, "共享契约");
        assertEquals("program_spec", sub.sourceKind());
        SubscriptionScanResult first = subscriptionService.scanDue();
        assertEquals(1, first.updated(), "集内订阅首扫应产 v1: " + first);

        apiSpecService.upload(1L, provider, "共享契约", V2.getBytes(StandardCharsets.UTF_8), null);
        forceDue(sub.id());
        SubscriptionScanResult second = subscriptionService.scanDue();
        assertEquals(1, second.updated(), "提供方改版后重扫应产 v2");

        Integer version = jdbcTemplate.queryForObject(
                "SELECT spec_version FROM tp_api_spec WHERE project_id = ? AND name = 'shared-api'"
                        + " ORDER BY spec_version DESC LIMIT 1", Integer.class, consumer);
        assertEquals(2, version, "消费方版本链应钉到 v2");

        Long previewTodo = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_api_spec_preview p JOIN tp_api_spec s ON p.spec_id = s.id"
                        + " WHERE s.project_id = ? AND s.spec_version = 2", Long.class, consumer);
        assertTrue(previewTodo != null && previewTodo >= 1, "集内改版须产预览待办");

        Long notified = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_notification WHERE event_type = 'SPEC_CHANGE_DETECTED'"
                        + " AND project_id = ?", Long.class, consumer);
        assertTrue(notified != null && notified >= 1, "集内改版须发 SPEC_CHANGE_DETECTED 站内信");
    }

    
    @Test
    void 集内内容一致转unchanged零通知() {
        createProject();
        long consumer = projectId;
        createProject();
        long provider = projectId;
        long program = createProgram("same");
        joinProgram(program, consumer);
        joinProgram(program, provider);
        apiSpecService.upload(1L, provider, "共享契约", V1.getBytes(StandardCharsets.UTF_8), null);
        SpecSubscriptionInfo sub = programSubscription(consumer, "stable-api", provider, "共享契约");
        subscriptionService.scanDue();

        forceDue(sub.id());
        SubscriptionScanResult again = subscriptionService.scanDue();
        assertEquals(1, again.unchanged(), "提供方内容未变=unchanged");
        String status = jdbcTemplate.queryForObject(
                "SELECT last_status FROM tp_spec_subscription WHERE id = ?",
                String.class, sub.id());
        assertEquals("unchanged", status);
    }

    
    @Test
    void 换源型重置到期且url源缺址400() {
        SpecSubscriptionInfo sub = createSubscription();
        served = V1.getBytes(StandardCharsets.UTF_8);
        subscriptionService.scanDue();
        assertNotNull(subscriptionService.get(sub.id()).lastCheckAt(), "URL 源首扫应记扫描时刻");

        createProject();
        long provider = projectId;
        long program = createProgram("switch");
        joinProgram(program, sub.projectId());
        joinProgram(program, provider);
        apiSpecService.upload(1L, provider, "共享契约", V1.getBytes(StandardCharsets.UTF_8), null);

        SpecSubscriptionInfo switched = subscriptionService.update(1L, sub.id(),
                new SpecSubscriptionSaveRequest(null, null, null, null, null,
                        "program_spec", provider, "共享契约"));
        assertEquals("program_spec", switched.sourceKind());
        assertNull(switched.url(), "集内行不保留 URL");
        assertEquals("pet-api", switched.name(), "空字段=保持原值");
        assertNull(switched.lastCheckAt(), "换源型=重置到期下轮立扫");

        ServiceException noUrl = assertThrows(ServiceException.class,
                () -> subscriptionService.update(1L, sub.id(), new SpecSubscriptionSaveRequest(
                        null, null, null, null, null, "url", null, null)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, noUrl.getCode());
        assertTrue(noUrl.getMessage().contains("http"), noUrl.getMessage());
    }
}
