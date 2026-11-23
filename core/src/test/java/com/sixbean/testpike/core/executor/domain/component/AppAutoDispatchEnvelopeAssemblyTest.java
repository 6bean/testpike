package com.sixbean.testpike.core.executor.domain.component;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.appauto.api.dto.AppautoEnvProfileInput;
import com.sixbean.testpike.core.appauto.domain.service.AppautoEnvProfileService;
import com.sixbean.testpike.core.executor.domain.service.ExecTaskService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
@org.springframework.test.context.TestPropertySource(properties = "testpike.scheduling.enabled=false")
class AppAutoDispatchEnvelopeAssemblyTest {

    
    private static final String LEGACY_ENGINE_JSON =
            "{\"code\":\"MOBILEWRIGHT\",\"platform\":\"ANDROID\",\"deviceKind\":\"EMULATOR\"}";

    @Autowired
    private AppAutoDispatchSupport appAutoDispatchSupport;

    @Autowired
    private ExecTaskService execTaskService;

    @Autowired
    private AppautoEnvProfileService envProfileService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder configCacheHolder;

    @Autowired
    private com.sixbean.testpike.core.appauto.domain.component.AppautoConfigReader appautoConfigReader;

    @Autowired
    private com.sixbean.testpike.core.executor.domain.service.ExecutorService executorService;

    private long projectId;
    private long otherProjectId;
    private long envProfileId;
    private final List<Long> createdTaskIds = new java.util.ArrayList<>();

    @BeforeEach
    void setUp() {
        String code = "asm" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "装配测试项目-" + code, code);
        projectId = jdbcTemplate.queryForObject("SELECT id FROM tp_project WHERE code = ?",
                Long.class, code);
        String otherCode = "asmo" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "装配他项目-" + otherCode, otherCode);
        otherProjectId = jdbcTemplate.queryForObject("SELECT id FROM tp_project WHERE code = ?",
                Long.class, otherCode);
        envProfileId = envProfileService.create(projectId, new AppautoEnvProfileInput(
                "装配安卓档案", "ANDROID_SDK",
                Map.of("sdkHome", "/opt/android-sdk", "avd", "demo_avd"), "装配切片夹具"));
        jdbcTemplate.update("DELETE FROM tp_executor WHERE name LIKE 'asm-ex-%'");
        executorService.register(new com.sixbean.testpike.core.executor.api.dto.ExecutorRegisterInput(
                "asm-ex-" + System.nanoTime(), null, null, "web,app", "chromium", 2, "1.0.0", 0,
                Map.of("engines", List.of("MOBILEWRIGHT"),
                        "platforms", List.of("ANDROID"),
                        "deviceKinds", List.of("EMULATOR"))));
    }

    @AfterEach
    void tearDown() {
 // drop cached vision keys so later tests re-read current DB values (seed defaults are on since the AI default-on batch)
        for (String k : new String[]{"ai.vision.enabled", "ai.vision.appauto-locate",
                "ai.appauto.locate.confidence"}) {
            configCacheHolder.remove(k);
        }
        for (Long taskId : createdTaskIds) {
            jdbcTemplate.update("DELETE FROM tp_exec_task WHERE id = ?", taskId);
        }
        jdbcTemplate.update("DELETE FROM tp_executor WHERE name LIKE 'asm-ex-%'");
        jdbcTemplate.update("DELETE FROM tp_appauto_popup_rule WHERE project_id IN (?, ?)",
                projectId, otherProjectId);
        jdbcTemplate.update("DELETE FROM tp_appauto_env_profile WHERE project_id IN (?, ?)",
                projectId, otherProjectId);
        jdbcTemplate.update("DELETE FROM tp_project WHERE id IN (?, ?)", projectId, otherProjectId);
    }

    private List<Map<String, Object>> launchSteps() {
        return List.of(Map.of("action", "app.launch", "target", Map.of("pkg", "com.demo")));
    }


    @Test
    void buildEnvelope_projects_full_descriptor_plus_env_profile_projection() {
        Map<String, Object> env = appAutoDispatchSupport.buildEnvelope(
                projectId, "MOBILEWRIGHT", envProfileId, launchSteps());
        assertEquals(AppAutoDispatchSupport.ENVELOPE_KIND, env.get("kind"));
        @SuppressWarnings("unchecked")
        Map<String, Object> engine = (Map<String, Object>) env.get("engine");
        assertNotNull(engine, "装配信封必须带 engine 描述符全面");
        assertEquals("MOBILEWRIGHT", engine.get("code"));
        assertEquals(1, engine.get("status"));
        assertTrue(engine.get("platformCodes") instanceof List<?> plats
                        && plats.contains("ANDROID"),
                "engine 必须含 platformCodes 全面（executor capability_violations 消费面）");
        assertTrue(engine.get("deviceKindCodes") instanceof List<?> kinds
                        && kinds.contains("EMULATOR"), "engine 必须含 deviceKindCodes 全面");
        assertNotNull(engine.get("runtimeType"), "engine 必须含 runtimeType（装载/体检消费位）");
        assertNotNull(engine.get("capabilities"), "engine 必须含 capabilities 动词位图");
        @SuppressWarnings("unchecked")
        Map<String, Object> profile = (Map<String, Object>) env.get("envProfile");
        assertNotNull(profile, "选定档案时信封必须带 envProfile 投影");
        assertEquals("/opt/android-sdk", profile.get("sdkHome"),
                "envProfile=档案 payload 键值原样投影");
        assertEquals(envProfileId, ((Number) profile.get("id")).longValue(),
                "档案 id 随投影下传（归因锚）");
        assertEquals(profile.get("profileKind"), env.get("envProfileKind"),
                "档案型随信封下传（报告面分型消费）");
        assertEquals(launchSteps(), env.get("steps"), "steps 原样透传禁改写");
    }

    @Test
    void buildEnvelope_blank_engine_falls_back_to_k8_default_engine() {
        Map<String, Object> env = appAutoDispatchSupport.buildEnvelope(
                projectId, null, null, launchSteps());
        @SuppressWarnings("unchecked")
        Map<String, Object> engine = (Map<String, Object>) env.get("engine");
        assertEquals("MOBILEWRIGHT", engine.get("code"),
                "引擎缺位=默认引擎解析单点（AppautoConfigReader，热更即生效）");
        assertFalse(env.containsKey("envProfile"), "未选档案=键不出（执行器按缺档案人话闸）");
    }


    @Test
    void buildEnvelope_rejects_cross_project_profile_in_plain_words() {
        long foreign = envProfileService.create(otherProjectId, new AppautoEnvProfileInput(
                "他项目档案", "MP_DEVTOOLS",
                Map.of("appid", "wx0", "projectPath", "D:/x"), null));
        ServiceException e = assertThrows(ServiceException.class, () ->
                appAutoDispatchSupport.buildEnvelope(projectId, "MOBILEWRIGHT", foreign,
                        launchSteps()));
        assertTrue(e.getMessage().contains("环境档案"), "人话点名档案: " + e.getMessage());
        assertTrue(e.getMessage().contains("项目"), "人话点名项目归属: " + e.getMessage());
    }

    @Test
    void buildEnvelope_rejects_disabled_engine_in_plain_words() {
        ServiceException e = assertThrows(ServiceException.class, () ->
                appAutoDispatchSupport.buildEnvelope(projectId, "HYPIUM", null, launchSteps()));
        assertTrue(e.getMessage().contains("HYPIUM"), "人话点名引擎: " + e.getMessage());
        assertTrue(e.getMessage().contains("停用") || e.getMessage().contains("下架"),
                "人话点名下架语义: " + e.getMessage());
    }

    @Test
    void buildEnvelope_rejects_unknown_engine_and_empty_steps() {
        assertThrows(ServiceException.class, () ->
                appAutoDispatchSupport.buildEnvelope(projectId, "NOT_AN_ENGINE", null,
                        launchSteps()));
        assertThrows(ServiceException.class, () ->
                appAutoDispatchSupport.buildEnvelope(projectId, "MOBILEWRIGHT", null, List.of()));
    }


    @Test
    void createAppAutoRun_persists_task_type6_schema_appauto_v1_sentinel_case() throws Exception {
        Map<String, Object> env = appAutoDispatchSupport.buildEnvelope(
                projectId, "MOBILEWRIGHT", envProfileId, launchSteps());
        String json = new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(env);
        long taskId = execTaskService.createAppAutoRun(projectId, json);
        createdTaskIds.add(taskId);
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT task_type, schema_version, case_id, status, project_id"
                        + " FROM tp_exec_task WHERE id = ?", taskId);
        assertEquals(6, ((Number) row.get("task_type")).intValue(), "task_type=6 分流");
        assertEquals("appauto-v1", row.get("schema_version"));
        assertEquals(0L, ((Number) row.get("case_id")).longValue(), "case_id=0 哨兵");
        assertEquals(1, ((Number) row.get("status")).intValue(), "落库即待派发");
        assertEquals(projectId, ((Number) row.get("project_id")).longValue());
    }

    @Test
    void createAppAutoRun_rejects_blank_and_foreign_project_and_bad_kind() {
        assertThrows(ServiceException.class, () -> execTaskService.createAppAutoRun(0, "{}"));
        assertThrows(ServiceException.class, () -> execTaskService.createAppAutoRun(
                otherProjectId, "{\"kind\":\"perf-run\"}"));
    }


    @Test
    @SuppressWarnings("unchecked")
    void buildEnvelope_projects_visualFallback_three_keys_from_k8_defaults() {
        Map<String, Object> env = appAutoDispatchSupport.buildEnvelope(
                projectId, "MOBILEWRIGHT", null, launchSteps());
        assertNotNull(env.get("visualFallback"),
                "信封必须尾追 visualFallback 键（executor app_worker 消费面，03 §2.3 登记）");
        Map<String, Object> vf = (Map<String, Object>) env.get("visualFallback");
 // seed defaults flipped to on by the AI default-on batch (2026-11-02 expansion): dev DB and schema-init both '1'
        assertEquals("1", vf.get("ai.vision.enabled"), "总闸缺省开位原样投影");
        assertEquals("1", vf.get("ai.vision.appauto-locate"), "分开关缺省开位原样投影");
        assertEquals("0.85,0.6", vf.get("ai.appauto.locate.confidence"),
                "置信阈值键原样下传（非法组合由执行器 parse 单点回退缺省，core 禁二套校验）");
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildEnvelope_visualFallback_hot_update_takes_effect_without_restart() {
        // default seed is on now (AI default-on batch); flip to explicit off to prove hot-update really re-reads
        configCacheHolder.put("ai.vision.enabled", "0");
        configCacheHolder.put("ai.vision.appauto-locate", "0");
        configCacheHolder.put("ai.appauto.locate.confidence", "0.9,0.7");
        Map<String, Object> env = appAutoDispatchSupport.buildEnvelope(
                projectId, "MOBILEWRIGHT", null, launchSteps());
        Map<String, Object> vf = (Map<String, Object>) env.get("visualFallback");
        assertEquals("0", vf.get("ai.vision.enabled"), "总闸热更即投影新值");
        assertEquals("0", vf.get("ai.vision.appauto-locate"), "分开关热更即投影新值");
        assertEquals("0.9,0.7", vf.get("ai.appauto.locate.confidence"),
                "阈值热更即投影新值（执行器侧重解析）");
    }


    @Test
    void validateEnvelope_accepts_legacy_three_dim_shape_for_existing_claims() {
        String legacy = "{\"kind\":\"app-auto-run\",\"runId\":1,\"leaseRef\":\"r1\",\"engine\":"
                + LEGACY_ENGINE_JSON + ",\"steps\":[]}";
        appAutoDispatchSupport.validateEnvelope(projectId, legacy);
        String wrong = "{\"kind\":\"app-auto-run\",\"engine\":{\"code\":\"XCUITEST\","
                + "\"platform\":\"ANDROID\",\"deviceKind\":\"REAL\"},\"steps\":[]}";
        assertThrows(ServiceException.class, () ->
                appAutoDispatchSupport.validateEnvelope(projectId, wrong));
    }

    @Test
    void quickappCarrierDimensionPassesAndroidFamilyDescriptorFace() {
        String ok = "{\"kind\":\"app-auto-run\",\"runId\":1,\"leaseRef\":\"r1\",\"engine\":"
                + "{\"code\":\"MOBILEWRIGHT\",\"platform\":\"QUICKAPP\","
                + "\"deviceKind\":\"EMULATOR\"},\"steps\":[]}";
        appAutoDispatchSupport.validateEnvelope(projectId, ok);
        String bad = "{\"kind\":\"app-auto-run\",\"engine\":{\"code\":\"XCUITEST\","
                + "\"platform\":\"QUICKAPP\",\"deviceKind\":\"REAL\"},\"steps\":[]}";
        ServiceException ex = assertThrows(ServiceException.class, () ->
                appAutoDispatchSupport.validateEnvelope(projectId, bad));
        assertTrue(ex.getMessage().contains("QUICKAPP"), "越界人话须点名平台维与非法值");
    }

    @Test
    void claim_face_skip_logic_holds_for_full_shape_and_legacy_alike() {
        Map<String, Object> capsOk = Map.of(
                "engines", List.of("MOBILEWRIGHT"), "platforms", List.of("ANDROID"),
                "deviceKinds", List.of("EMULATOR"));
        Map<String, Object> capsNoEngine = Map.of(
                "engines", List.of("MAESTRO"), "platforms", List.of("ANDROID"),
                "deviceKinds", List.of("EMULATOR"));
        String full = toJson(appAutoDispatchSupport.buildEnvelope(
                projectId, "MOBILEWRIGHT", null, launchSteps()));
        assertFalse(appAutoDispatchSupport.shouldSkipForExecutor(full, capsOk),
                "全面形信封对匹配执行器不跳过（claim 面按 code 维对账）");
        assertTrue(appAutoDispatchSupport.shouldSkipForExecutor(full, capsNoEngine),
                "全面形信封对无该引擎能力执行器跳过排队（非异常）");
        String legacy = "{\"kind\":\"app-auto-run\",\"engine\":" + LEGACY_ENGINE_JSON
                + ",\"steps\":[]}";
        assertFalse(appAutoDispatchSupport.shouldSkipForExecutor(legacy, capsOk),
                "三维小形存量形态行为不变（红线：旧链路零破坏）");
        assertTrue(appAutoDispatchSupport.shouldSkipForExecutor(legacy, capsNoEngine));
    }

    @Test
    void createAppAutoRun_preflight_no_capable_executor_rejects_in_plain_words_zero_rows() {
        String json = toJson(appAutoDispatchSupport.buildEnvelope(
                projectId, "MAESTRO", null, launchSteps()));
        int before = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_exec_task WHERE project_id = ?",
                Integer.class, projectId);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> execTaskService.createAppAutoRun(projectId, json));
        assertTrue(ex.getMessage().contains("MAESTRO"), "拒因点名所需引擎: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("0 台"), "拒因如实报可执行台数: " + ex.getMessage());
        assertFalse(ex.getMessage().contains("{"), "拒因零 JSON dump: " + ex.getMessage());
        int after = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_exec_task WHERE project_id = ?",
                Integer.class, projectId);
        assertEquals(before, after, "秒拒=零落库（不建任务行不占队列位）");
    }

    // ── ⑤ popup-rule stamping: approved project rows ride the dispatch envelope ──

    @Test
    void buildEnvelope_stamps_approved_rules_in_consumer_shape() {
        long approved = insertPopupRule(projectId, 2, "RAM 优化弹窗",
                "{\"detect\":{\"text\":\"RAM cleanup\"},"
                        + "\"dismiss\":{\"action\":\"app.tap\",\"target\":{\"text\":\"Close\"}}}");
        insertPopupRule(projectId, 1, "pending draft", "{\"detect\":{\"text\":\"x\"},\"dismiss\":{}}");
        insertPopupRule(projectId, 3, "rejected draft", "{\"detect\":{\"text\":\"y\"},\"dismiss\":{}}");
        insertPopupRule(otherProjectId, 2, "foreign approved", "{\"detect\":{\"text\":\"z\"}}");
        Map<String, Object> envelope = appAutoDispatchSupport.buildEnvelope(
                projectId, "MOBILEWRIGHT", envProfileId, launchSteps());
        assertNotNull(envelope.get("popupRules"),
                "production envelope must always carry the popupRules key");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> stamped =
                (List<Map<String, Object>>) envelope.get("popupRules");
        assertEquals(1, stamped.size(),
                "only status=2 rows of this project ride the envelope");
        Map<String, Object> row = stamped.get(0);
        assertEquals(approved, ((Number) row.get("id")).longValue(), "row carries its rule id");
        assertEquals("RAM 优化弹窗", row.get("title"), "row carries the rule title");
        assertTrue(row.get("rule") instanceof Map<?, ?> body && !body.isEmpty(),
                "rule must be a parsed object — the app_worker consumer reads rule.rule as a dict");
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) row.get("rule");
        assertEquals("app.tap",
                ((Map<?, ?>) body.get("dismiss")).get("action"),
                "dismiss payload survives the parse untouched");
    }

    @Test
    void buildEnvelope_ships_empty_popupRules_when_library_is_silent() {
        Map<String, Object> envelope = appAutoDispatchSupport.buildEnvelope(
                projectId, "MOBILEWRIGHT", envProfileId, launchSteps());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> stamped =
                (List<Map<String, Object>>) envelope.get("popupRules");
        assertNotNull(stamped, "the key is always present — the executor never has to guess");
        assertTrue(stamped.isEmpty(), "no approved rows = empty list, self-heal stays a silent bonus path");
    }

    @Test
    void buildEnvelope_drops_unparsable_rows_without_failing_the_envelope() {
        insertPopupRule(projectId, 2, "json array row", "[1,2]");
        long good = insertPopupRule(projectId, 2, "object row",
                "{\"detect\":{\"text\":\"a\"},\"dismiss\":{\"action\":\"app.tap\",\"target\":{\"text\":\"b\"}}}");
        Map<String, Object> envelope = appAutoDispatchSupport.buildEnvelope(
                projectId, "MOBILEWRIGHT", envProfileId, launchSteps());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> stamped =
                (List<Map<String, Object>>) envelope.get("popupRules");
        assertEquals(1, stamped.size(), "non-object rule payloads are dropped, not shipped");
        assertEquals(good, ((Number) stamped.get(0).get("id")).longValue(),
                "valid rows are kept regardless of ordering");
    }

    @Test
    void envelope_stamp_limit_is_configurable_not_hardcoded() {
        int limit = appautoConfigReader.popupRuleEnvelopeLimit();
        assertTrue(limit > 0, "the stamping limit must resolve to a positive hot key");
        // Capture the shipped seed value before planting: the fixture must repay,
        // never delete the factory row (R37: baseline stays resident after tests).
        List<String> prior = jdbcTemplate.queryForList(
                "SELECT config_value FROM tp_sys_config WHERE config_key = 'appauto.popup-rule-envelope-limit'",
                String.class);
        jdbcTemplate.update(
                "INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag)"
                        + " VALUES ('appauto.popup-rule-envelope-limit', '3', 'APP自动化', 'int', 0)"
                        + " ON CONFLICT (config_key) DO UPDATE SET config_value = '3'");
        try {
            configCacheHolder.remove("appauto.popup-rule-envelope-limit");
            assertEquals(3, appautoConfigReader.popupRuleEnvelopeLimit(),
                    "the limit follows the hot config value (no hardcode)");
        } finally {
            if (!prior.isEmpty()) {
                jdbcTemplate.update("UPDATE tp_sys_config SET config_value = ?"
                                + " WHERE config_key = 'appauto.popup-rule-envelope-limit'",
                        prior.get(0));
            } else {
                jdbcTemplate.update("DELETE FROM tp_sys_config WHERE config_key = 'appauto.popup-rule-envelope-limit'");
            }
            configCacheHolder.remove("appauto.popup-rule-envelope-limit");
        }
    }

    /** Insert one popup-rule row (test fixture writes the JSONB column directly). */
    private long insertPopupRule(long project, int status, String title, String ruleJson) {
        jdbcTemplate.update("INSERT INTO tp_appauto_popup_rule (project_id, title, rule, status)"
                + " VALUES (?, ?, ?::jsonb, ?)", project, title, ruleJson, status);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM tp_appauto_popup_rule WHERE project_id = ? AND title = ?",
                Long.class, project, title);
    }

    private String toJson(Map<String, Object> envelope) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(envelope);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
