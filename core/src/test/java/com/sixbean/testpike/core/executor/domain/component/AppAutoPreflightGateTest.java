package com.sixbean.testpike.core.executor.domain.component;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import com.sixbean.testpike.core.credential.api.dto.CredentialInput;
import com.sixbean.testpike.core.credential.domain.service.CredentialService;
import com.sixbean.testpike.core.executor.api.dto.ExecutorRegisterInput;
import com.sixbean.testpike.core.executor.domain.service.ExecutorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class AppAutoPreflightGateTest {

    private static final String SEEN_KEY = "appauto.device-seen-window-seconds";

    
    private static final String ENGINE_DIMS =
            "{\"code\":\"MOBILEWRIGHT\",\"platform\":\"ANDROID\",\"deviceKind\":\"EMULATOR\"}";

    private static final String BROWSER_ACTION =
            "{\"version\":\"v2\",\"steps\":[{\"action\":\"goto\",\"target\":{\"url\":\"http://d.l\"}}]}";

    @Autowired
    private AppAutoPreflightGate gate;

    @Autowired
    private ExecutorService executorService;

    @Autowired
    private CredentialService credentialService;

    @Autowired
    private ConfigCacheHolder configCacheHolder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;
    private String serial;

    @BeforeEach
    void setUp() {
        String code = "gtx" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "健康闸测试项目-" + code, code);
        projectId = jdbcTemplate.queryForObject("SELECT id FROM tp_project WHERE code = ?",
                Long.class, code);
        serial = "gate-dev-" + System.nanoTime();
        cleanOwnRows();
    }

    @AfterEach
    void tearDown() {
        cleanOwnRows();
        configCacheHolder.remove(SEEN_KEY);
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
    }

    private void cleanOwnRows() {
        jdbcTemplate.update("DELETE FROM tp_appauto_lease_run WHERE device_id IN"
                + " (SELECT id FROM tp_appauto_device WHERE serial LIKE 'gate-dev-%')");
        jdbcTemplate.update("DELETE FROM tp_appauto_device WHERE serial LIKE 'gate-dev-%'");
        jdbcTemplate.update("DELETE FROM tp_appauto_app WHERE package_name LIKE 'com.gatepkg.%'");
        jdbcTemplate.update("DELETE FROM tp_credential WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_executor WHERE name LIKE 'gate-ex-%'");
    }


    private String envelope(String stepsJson, boolean withLeaseRef) {
        return envelope(stepsJson, withLeaseRef, ENGINE_DIMS);
    }

    private String envelope(String stepsJson, boolean withLeaseRef, String engineDims) {
        return "{\"kind\":\"app-auto-run\",\"runId\":1,"
                + (withLeaseRef ? "\"leaseRef\":{\"deviceId\":1,\"serial\":\"" + serial + "\"}," : "")
                + "\"engine\":" + engineDims + ",\"steps\":[" + stepsJson + "]}";
    }

    private String launchStep() {
        return "{\"action\":\"app.launch\",\"target\":{\"pkg\":\"com.demo\"}}";
    }

    
    private void registerExecutor(String engineCode) {
        executorService.register(new ExecutorRegisterInput(
                "gate-ex-" + System.nanoTime(), null, null, "web,app", "chromium", 2, "1.0.0", 0,
                Map.of("engines", List.of(engineCode),
                        "platforms", List.of("ANDROID"),
                        "deviceKinds", List.of("EMULATOR"))));
    }

    private void insertDevice(String healthState, String seenInterval, String overheatInterval) {
        jdbcTemplate.update("""
                INSERT INTO tp_appauto_device (project_id, name, serial, device_type,
                    health_state, last_seen_at, last_overheat_at)
                SELECT ?, 'gate-dev', ?, 'ADB', ?,
                    CASE WHEN (?::text) IS NULL THEN NULL
                         ELSE now() - (?::interval) END,
                    CASE WHEN (?::text) IS NULL THEN NULL
                         ELSE now() - (?::interval) END
                """, projectId, serial, healthState,
                seenInterval, seenInterval,
                overheatInterval, overheatInterval);
    }

    private void insertAppArchive(String pkg, String artifactKey) {
        jdbcTemplate.update("INSERT INTO tp_appauto_app (project_id, name, package_name,"
                + " version, apk_artifact_key) VALUES (?, 'gate-app', ?, '1.0', ?)",
                projectId, pkg, artifactKey);
    }


    @Test
    void explain_非app信封_直通放行() {
        assertNull(gate.explainFailure(projectId, BROWSER_ACTION), "浏览器信封零打扰");
        assertNull(gate.explainFailure(projectId, "not-json"), "脏 JSON 直通交消费侧（本闸不判脏单）");
        assertNull(gate.explainFailure(projectId, null), "空动作直通");
    }


    @Test
    void explain_无匹配引擎_人话点名引擎码与台数() {
        registerExecutor("MAESTRO");
        String harmonyDims =
                "{\"code\":\"HMNEXTAUTO\",\"platform\":\"HARMONY\",\"deviceKind\":\"EMULATOR\"}";
        String reason = gate.explainFailure(projectId, envelope(launchStep(), false, harmonyDims));
        assertNotNull(reason, "在线台无 HMNEXTAUTO 能力=必拒");
        assertTrue(reason.contains("HMNEXTAUTO"), "必须点名所需引擎: " + reason);
        assertTrue(reason.contains("0 台"), "必须如实报可执行台数: " + reason);
    }

    @Test
    void explain_能力匹配且无设备引用_放行() {
        registerExecutor("MOBILEWRIGHT");
        assertNull(gate.explainFailure(projectId, envelope(launchStep(), false)),
                "无 leaseRef=设备闸诚实缺位不加闸（派发闸同口径）");
    }


    @Test
    void explain_隔离态设备_拦() {
        registerExecutor("MOBILEWRIGHT");
        insertDevice("QUARANTINED", "10 seconds", null);
        String reason = gate.explainFailure(projectId, envelope(launchStep(), true));
        assertNotNull(reason);
        assertTrue(reason.contains("隔离"), "必须人话点名隔离: " + reason);
    }

    @Test
    void explain_探活缺失或超窗_拦_窗内新鲜过() {
        registerExecutor("MOBILEWRIGHT");
        insertDevice("GREEN", null, null);
        String reason = gate.explainFailure(projectId, envelope(launchStep(), true));
        assertNotNull(reason, "last_seen_at 缺失=判离线必拒");
        assertTrue(reason.contains("探活"), "必须点名探活缺失: " + reason);

        configCacheHolder.put(SEEN_KEY, "1");
        insertDeviceAfterClean("GREEN", "30 seconds", null);
        assertNotNull(gate.explainFailure(projectId, envelope(launchStep(), true)),
                "超探活窗=判离线必拒");

        configCacheHolder.put(SEEN_KEY, "3600");
        insertDeviceAfterClean("GREEN", "10 seconds", null);
        assertNull(gate.explainFailure(projectId, envelope(launchStep(), true)),
                "窗内新鲜=放行");
    }

    private void insertDeviceAfterClean(String health, String seenInterval, String overheatInterval) {
        jdbcTemplate.update("DELETE FROM tp_appauto_device WHERE serial = ?", serial);
        insertDevice(health, seenInterval, overheatInterval);
    }


    @Test
    void explain_过热保护窗内_拦并带电量存储未采集注记() {
        registerExecutor("MOBILEWRIGHT");
        insertDevice("GREEN", "10 seconds", "2 minutes");
        String reason = gate.explainFailure(projectId, envelope(launchStep(), true));
        assertNotNull(reason, "过热窗内（缺省 10 分钟）=必拒");
        assertTrue(reason.contains("过热"), "必须人话点名过热: " + reason);
        assertTrue(reason.contains("未采集"), "电量/存储无列=诚实注记禁假闸: " + reason);
    }

    @Test
    void explain_过热窗外_放行() {
        registerExecutor("MOBILEWRIGHT");
        insertDevice("GREEN", "10 seconds", "5 hours");
        assertNull(gate.explainFailure(projectId, envelope(launchStep(), true)),
                "过热窗已自愈=不拦（T329 窗宽口径同源）");
    }


    @Test
    void explain_install包无档案_秒拒点名包名() {
        registerExecutor("MOBILEWRIGHT");
        String step = "{\"action\":\"app.install\",\"pkg\":\"com.gatepkg.demo\"}";
        String reason = gate.explainFailure(projectId, envelope(step, false));
        assertNotNull(reason);
        assertTrue(reason.contains("com.gatepkg.demo"), "必点名包名: " + reason);
        assertTrue(reason.contains("建档"), "指路建档: " + reason);
    }

    @Test
    void explain_install档案缺工件_秒拒() {
        registerExecutor("MOBILEWRIGHT");
        insertAppArchive("com.gatepkg.demo", null);
        String step = "{\"action\":\"app.install\",\"pkg\":\"com.gatepkg.demo\"}";
        String reason = gate.explainFailure(projectId, envelope(step, false));
        assertNotNull(reason, "install 缺上传工件=跑前秒拒（执行器下载闸前移）");
        assertTrue(reason.contains("工件"), "必点名工件缺失: " + reason);
    }

    @Test
    void explain_install带工件或verifyVersion_放行() {
        registerExecutor("MOBILEWRIGHT");
        insertAppArchive("com.gatepkg.demo", "app-package/gate/demo.apk");
        assertNull(gate.explainFailure(projectId,
                envelope("{\"action\":\"app.install\",\"pkg\":\"com.gatepkg.demo\"}", false)),
                "档案+工件齐=放行");
        insertDevice("GREEN", "5 seconds", null);
        jdbcTemplate.update("DELETE FROM tp_appauto_app WHERE package_name = ? AND project_id = ?",
                "com.gatepkg.demo", projectId);
        insertAppArchive("com.gatepkg.demo", null);
        assertNull(gate.explainFailure(projectId,
                envelope("{\"action\":\"app.verifyVersion\",\"pkg\":\"com.gatepkg.demo\","
                        + "\"expectVersion\":\"1.0\"}", true)),
                "verifyVersion 不要求工件（读口对账动词）");
    }


    @Test
    void explain_凭据未建档_人话点名键() {
        registerExecutor("MOBILEWRIGHT");
        String step = "{\"action\":\"app.input\",\"value\":\"{{CRED:gate_login}}\"}";
        String reason = gate.explainFailure(projectId, envelope(step, false));
        assertNotNull(reason, "引用未建档键=必拒（存在/启用/可解三合一）");
        assertTrue(reason.contains("gate_login"), "必点名引用键: " + reason);
    }

    @Test
    void explain_凭据启用建档_放行_禁用_拦() {
        registerExecutor("MOBILEWRIGHT");
        String step = "{\"action\":\"app.input\",\"value\":\"{{CRED:gate_login}}\"}";
        credentialService.create(projectId,
                new CredentialInput("闸测试凭据", "gate_login", "s3cret-真实入密", null));
        assertNull(gate.explainFailure(projectId, envelope(step, false)),
                "建档启用可解=放行");

        jdbcTemplate.update("UPDATE tp_credential SET status = 0 WHERE project_id = ?"
                + " AND cred_key = 'gate_login'", projectId);
        String reason = gate.explainFailure(projectId, envelope(step, false));
        assertNotNull(reason, "禁用键=必拒");
        assertTrue(reason.contains("禁用") || reason.contains("gate_login"), "人话点名: " + reason);
    }

    @Test
    void explain_五闸全绿_放行() {
        registerExecutor("MOBILEWRIGHT");
        insertDevice("GREEN", "10 seconds", null);
        insertAppArchive("com.gatepkg.demo", "app-package/gate/demo.apk");
        credentialService.create(projectId,
                new CredentialInput("闸测试凭据", "gate_login", "s3cret-真实入密", null));
        String steps = "{\"action\":\"app.install\",\"pkg\":\"com.gatepkg.demo\"},"
                + "{\"action\":\"app.input\",\"value\":\"{{CRED:gate_login}}\"}";
        assertNull(gate.explainFailure(projectId, envelope(steps, true)),
                "运行时在+设备新鲜+档案工件齐+凭据可解=全放行");
    }
}
