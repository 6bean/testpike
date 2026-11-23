package com.sixbean.testpike.core.explore.domain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.appauto.api.dto.AppautoDeviceInput;
import com.sixbean.testpike.core.appauto.domain.component.AppautoLeaseRunRecorder;
import com.sixbean.testpike.core.appauto.domain.service.AppautoDeviceService;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import com.sixbean.testpike.core.credential.api.dto.CredentialInput;
import com.sixbean.testpike.core.credential.domain.service.CredentialService;
import com.sixbean.testpike.core.explore.api.dto.ExploreCreateInput;
import com.sixbean.testpike.core.support.LeaseNamespaceExtension;
import com.sixbean.testpike.core.support.LeaseRefNamespace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
@ExtendWith(LeaseNamespaceExtension.class)
class ExploreAppautoSliceATest {

    private static final String SWITCH_KEY = "ai.explore.appauto";
    private static final String EXPLORE_TTL_KEY = "appauto.lease-ttl-explore";
    private static final String DEVICE_TTL_KEY = "appauto.device-lease-ttl-seconds";

    
    private static final String SEMANTIC_MOBILE_ACT =
            "{\"kind\":\"explore-act\",\"target\":\"mobile\",\"action\":\"tap\","
                    + "\"arguments\":{\"selector\":\"desc=提交订单\"}}";

    @Autowired
    private ExploreService exploreService;

    @Autowired
    private AppautoDeviceService deviceService;

    @Autowired
    private CredentialService credentialService;

    @Autowired
    private AppautoLeaseRunRecorder leaseRunRecorder;

    @Autowired
    private ConfigCacheHolder configCacheHolder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private long projectId;
    private long envId;

    @BeforeEach
    void setUp() {
        String code = "eas" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "App探索切片A项目-" + code, code);
        projectId = jdbcTemplate.queryForObject("SELECT id FROM tp_project WHERE code = ?",
                Long.class, code);
        jdbcTemplate.update("INSERT INTO tp_project_env (project_id, name, env_type, base_url,"
                        + " version_snapshot) VALUES (?, '探索测试环境', 2, 'http://localhost:5173', 1)",
                projectId);
        envId = jdbcTemplate.queryForObject(
                "SELECT MAX(id) FROM tp_project_env WHERE project_id = ?", Long.class, projectId);
        configCacheHolder.put(SWITCH_KEY, "true");
    }

    @AfterEach
    void tearDown() {
        configCacheHolder.put(SWITCH_KEY, "false");
        configCacheHolder.remove(EXPLORE_TTL_KEY);
        configCacheHolder.remove(DEVICE_TTL_KEY);
        configCacheHolder.remove("ai.explore.appauto.max-steps");
        configCacheHolder.remove("ai.explore.appauto.max-tokens");
        configCacheHolder.remove("ai.explore.appauto.max-duration-seconds");
        jdbcTemplate.update("DELETE FROM tp_exec_task WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_explore_step WHERE session_id IN"
                + " (SELECT id FROM tp_explore_session WHERE project_id = ?)", projectId);
        jdbcTemplate.update("DELETE FROM tp_explore_session WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_credential WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_appauto_lease_run WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_appauto_device WHERE project_id = ?", projectId);
        // Lease rows are owned by the run-scoped namespace: LeaseNamespaceExtension removes
        // exactly this suite's refs (blanket TRUNCATE wiped concurrent suites' leases on the dev DB).
        jdbcTemplate.update("DELETE FROM tp_project_env WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
    }

    private long createMobileSession(String credRef) {
        return exploreService.create(1L, projectId, new ExploreCreateInput(
                "漫游下单主流程找崩溃点", "app://com.demo.shop", envId, credRef));
    }

    private String envelopeOf(long taskId) {
        return jdbcTemplate.queryForObject(
                "SELECT action_json::text FROM tp_exec_task WHERE id = ?", String.class, taskId);
    }

    private long registerRealDevice(LeaseRefNamespace ns, String tag) {
        return deviceService.register(projectId, new AppautoDeviceInput(
                "探索机" + tag, serial(ns, tag), "EMULATOR", null,
                "Pixel 8", "14", "1080x2400", 420, List.of("explore")));
    }

    /** Device serial owned by this run's lease namespace, so teardown only deletes our rows. */
    private String serial(LeaseRefNamespace ns, String tag) {
        return ns.ref("EAS-" + tag + "-" + projectId);
    }


    
    @Test
    void createMobileSession_persistsEnvIdAndCredRef_withAppautoBudgetSegmentInSnapshot()
            throws Exception {
        configCacheHolder.put("ai.explore.appauto.max-steps", "70");
        configCacheHolder.put("ai.explore.appauto.max-tokens", "770000");
        configCacheHolder.put("ai.explore.appauto.max-duration-seconds", "2400");
        long credId = credentialService.create(projectId,
                new CredentialInput("探索账号", "eas_pool", "S3cret-秘密", "样例 App 测试账号"));
        assertTrue(credId > 0);
        long sessionId = createMobileSession("eas_pool");
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT env_id, cred_ref, budget_snapshot::text AS snap FROM tp_explore_session"
                        + " WHERE id = ?", sessionId);
        assertEquals(envId, ((Number) row.get("env_id")).longValue(),
                "移动会话必须持久化环境归属——切片B 闸只做瞬时校验，建完即丢=无法二次核对");
        assertEquals("eas_pool", row.get("cred_ref"),
                "凭据引用=键名非 id（占位符 {{CRED:键}} 同源）");
        JsonNode snap = objectMapper.readTree((String) row.get("snap"));
        JsonNode appauto = snap.path("appauto");
        assertTrue(snap.has("appauto"), "移动会话快照必含三重预算段: " + snap);
        assertEquals(70, appauto.path("maxSteps").asInt(), "步数线取键现读值");
        assertEquals(770000, appauto.path("maxTokens").asInt(), "token 线取键现读值");
        assertEquals(2400, appauto.path("maxDurationSeconds").asInt(), "时长线取键现读值");
        configCacheHolder.put("ai.explore.appauto.max-steps", "999");
        String again = jdbcTemplate.queryForObject(
                "SELECT budget_snapshot::text FROM tp_explore_session WHERE id = ?",
                String.class, sessionId);
        assertEquals(objectMapper.readTree(again), snap, "预算快照=创建时刻定版，热改只影响新会话");
    }

    
    @Test
    void createWebSession_snapshotUnchanged_withoutAppautoSegment() throws Exception {
        long sessionId = exploreService.create(1L, projectId,
                new ExploreCreateInput("摸清登录流程", "http://localhost:5173"));
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT env_id, cred_ref, budget_snapshot::text AS snap FROM tp_explore_session"
                        + " WHERE id = ?", sessionId);
        assertEquals(null, row.get("env_id"), "web 形态 env_id 保持 NULL");
        assertEquals(null, row.get("cred_ref"), "web 形态 cred_ref 保持 NULL");
        assertFalse(objectMapper.readTree((String) row.get("snap")).has("appauto"),
                "web 快照禁混入移动预算段（分线计量=坑⑦）");
    }


    
    @Test
    void submitAct_mobileSemantic_enrichedWithEngineLeaseRefRunId(LeaseRefNamespace ns) throws Exception {
        long deviceId = registerRealDevice(ns, "R1");
        long sessionId = createMobileSession(null);
        long taskId = exploreService.submitAct(sessionId, SEMANTIC_MOBILE_ACT);
        JsonNode env = objectMapper.readTree(envelopeOf(taskId));
        assertEquals("explore-act", env.path("kind").asText());
        assertEquals("mobile", env.path("target").asText(), "语义字段原样保留");
        assertEquals("tap", env.path("action").asText());
        assertEquals("desc=提交订单", env.path("arguments").path("selector").asText(),
                "arguments 原样透传禁改写");
        assertEquals("MOBILEWRIGHT", env.path("engine").path("code").asText(),
                "engine 缺省走 appauto.default-engine 解析单点（执行器无会话语境禁猜引擎）");
        assertTrue(env.path("engine").has("deviceKindCodes"), "描述符全面投影（非仅三维）");
        assertEquals(serial(ns, "R1"), env.path("leaseRef").path("serial").asText(),
                "leaseRef.serial 取解析命中档案");
        assertEquals(deviceId, env.path("leaseRef").path("deviceId").asLong(),
                "leaseRef.deviceId=解析命中的档案行 id");
        assertEquals("explore-" + sessionId, env.path("runId").asText(), "runId 会话线随信封自含");
    }

    
    @Test
    void submitAct_mobileNoDevice_rejectedHumanLanguage() {
        long sessionId = createMobileSession(null);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> exploreService.submitAct(sessionId, SEMANTIC_MOBILE_ACT));
        assertTrue(ex.getMessage().contains("设备"), "拒语必须人话点名设备缺位: " + ex.getMessage());
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_exec_task WHERE project_id = ?", Integer.class, projectId)
                .intValue(), "拒单零任务行");
    }

    
    @Test
    void submitAct_sessionCredRef_injectedWithoutPlaceholderLeftover(LeaseRefNamespace ns) throws Exception {
        registerRealDevice(ns, "R2");
        credentialService.create(projectId,
                new CredentialInput("探索账号2", "eas_pool2", "P@ss-实值", "池账号"));
        long sessionId = createMobileSession("eas_pool2");
        long taskId = exploreService.submitAct(sessionId, SEMANTIC_MOBILE_ACT);
        String envelope = envelopeOf(taskId);
        assertFalse(envelope.contains("{{CRED:"),
                "派发信封禁残留占位（assertNoLeftover 后置守门）: " + envelope);
        assertEquals("P@ss-实值", objectMapper.readTree(envelope)
                .path("arguments").path("cred").asText(), "会话预置凭据实值注入 arguments.cred");
    }

    
    @Test
    void submitAct_kernelCredReference_resolvedByInjector(LeaseRefNamespace ns) throws Exception {
        registerRealDevice(ns, "R3");
        credentialService.create(projectId,
                new CredentialInput("探索账号3", "eas_key3", "V@lue-3", "池账号3"));
        long sessionId = createMobileSession(null);
        long taskId = exploreService.submitAct(sessionId,
                "{\"kind\":\"explore-act\",\"target\":\"mobile\",\"action\":\"tap\","
                        + "\"arguments\":{\"password\":\"{{CRED:eas_key3}}\"}}");
        String envelope = envelopeOf(taskId);
        assertFalse(envelope.contains("{{CRED:"), "内核自带引用同样注入: " + envelope);
        assertEquals("V@lue-3", objectMapper.readTree(envelope)
                .path("arguments").path("password").asText());
    }

    
    @Test
    void submitAct_webEnvelope_passthroughUntouched() throws Exception {
        long sessionId = exploreService.create(1L, projectId,
                new ExploreCreateInput("web 目标", "http://localhost:5173"));
        long taskId = exploreService.submitAct(sessionId,
                "{\"kind\":\"explore-act\",\"action\":\"page-snapshot\","
                        + "\"url\":\"http://localhost:5173\"}");
        JsonNode env = objectMapper.readTree(envelopeOf(taskId));
        assertEquals("page-snapshot", env.path("action").asText());
        assertEquals("http://localhost:5173", env.path("url").asText());
        assertFalse(env.has("engine"), "web 信封零冲击——富化件不装三要素");
        assertFalse(env.has("leaseRef"), "web 信封零冲击——不装设备寻址");
        assertFalse(env.has("runId"), "web 信封零冲击——不装会话线");
    }


    
    @Test
    void exploreTierAcquire_usesExploreTtlWindow_andWritesFlowRow(LeaseRefNamespace ns) {
        configCacheHolder.put(EXPLORE_TTL_KEY, "7200");
        configCacheHolder.put(DEVICE_TTL_KEY, "1800");
        long deviceId = registerRealDevice(ns, "T1");
        leaseRunRecorder.acquire(deviceId, projectId, serial(ns, "T1"), null,
                "holder-exp", AppautoLeaseRunRecorder.KIND_EXPLORE);
        Map<String, Object> lease = jdbcTemplate.queryForMap(
                "SELECT kind, EXTRACT(EPOCH FROM (expires_at - acquired_at))::int AS ttl"
                        + " FROM tp_resource_lease WHERE resource_ref = ? AND state = 'active'",
                serial(ns, "T1"));
        assertEquals("explore", lease.get("kind"),
                "探索占用落 kind=explore 档（与常规 device 互不阻塞）");
        assertEquals(7200, ((Number) lease.get("ttl")).intValue(),
                "探索档租期=appauto.lease-ttl-explore 现读（V196 键转正）");
        Integer flow = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_appauto_lease_run WHERE device_id = ? AND state = 'ACTIVE'",
                Integer.class, deviceId);
        assertEquals(1, flow.intValue(), "流水展示面照常写（占用可见性不分档）");
    }

    
    @Test
    void deviceTierAcquire_unchanged(LeaseRefNamespace ns) {
        configCacheHolder.put(EXPLORE_TTL_KEY, "7200");
        configCacheHolder.put(DEVICE_TTL_KEY, "1800");
        long deviceId = registerRealDevice(ns, "T2");
        leaseRunRecorder.acquire(deviceId, projectId, serial(ns, "T2"), null, "holder-dev");
        String kind = jdbcTemplate.queryForObject(
                "SELECT kind FROM tp_resource_lease WHERE resource_ref = ?", String.class,
                serial(ns, "T2"));
        Integer ttl = jdbcTemplate.queryForObject(
                "SELECT EXTRACT(EPOCH FROM (expires_at - acquired_at))::int FROM tp_resource_lease"
                        + " WHERE resource_ref = ?", Integer.class, serial(ns, "T2"));
        assertEquals("device", kind, "常规占用仍 kind=device");
        assertEquals(1800, ttl.intValue(), "常规档读 device 键，探索键不越权");
    }

    
    @Test
    void exploreHolder_renewAndReleaseSameTierOnly(LeaseRefNamespace ns) {
        configCacheHolder.put(EXPLORE_TTL_KEY, "7200");
        long deviceId = registerRealDevice(ns, "T3");
        String serial = serial(ns, "T3");
        leaseRunRecorder.acquire(deviceId, projectId, serial, null, "h3",
                AppautoLeaseRunRecorder.KIND_EXPLORE);
        assertFalse(leaseRunRecorder.renew(serial, "h3"),
                "device 档续租打不到 explore 档占用（分档后禁跨档误延寿）");
        assertTrue(leaseRunRecorder.renew(serial, "h3", AppautoLeaseRunRecorder.KIND_EXPLORE),
                "explore 档持有者同档续租正常");
        leaseRunRecorder.release(deviceId, serial, "h3", "探索会话结束",
                AppautoLeaseRunRecorder.KIND_EXPLORE);
        assertEquals("RELEASED", jdbcTemplate.queryForObject(
                "SELECT state FROM tp_appauto_lease_run WHERE device_id = ?", String.class, deviceId),
                "归还归因流水同档收口");
    }

    
    @Test
    void sweepExpired_coversBothTiers(LeaseRefNamespace ns) {
        configCacheHolder.put(EXPLORE_TTL_KEY, "7200");
        long d1 = registerRealDevice(ns, "S1");
        leaseRunRecorder.acquire(d1, projectId, serial(ns, "S1"), null, "h1",
                AppautoLeaseRunRecorder.KIND_EXPLORE);
        jdbcTemplate.update("UPDATE tp_resource_lease SET expires_at = now() - interval '1 second'"
                + " WHERE resource_ref = ?", serial(ns, "S1"));
        int swept = leaseRunRecorder.sweepExpired();
        assertTrue(swept >= 1, "explore 档过期必须被 sweep 回收（防占机泄漏）");
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_resource_lease WHERE resource_ref = ? AND state = 'active'",
                Integer.class, serial(ns, "S1")).intValue());
        assertEquals("EXPIRED", jdbcTemplate.queryForObject(
                "SELECT state FROM tp_appauto_lease_run WHERE device_id = ?", String.class, d1));
    }
}
