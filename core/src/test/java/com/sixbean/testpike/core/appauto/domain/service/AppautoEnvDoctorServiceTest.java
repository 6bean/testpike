package com.sixbean.testpike.core.appauto.domain.service;

import com.sixbean.testpike.core.appauto.api.dto.AppautoEngineHealthInfo;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * key ships ON by default (AI default-on batch; the flag is projected as-is while the
 * deterministic zero-LLM behaviour of this service never depends on the key value).
 */
@SpringBootTest(classes = com.sixbean.testpike.core.CoreApplication.class)
class AppautoEnvDoctorServiceTest {

    @Autowired
    private AppautoEnvDoctorService envDoctorService;
    @Autowired
    private ExecutorService executorService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    // Fixture-scoped teardown (shared-dev-db tranche): removes only this suite's
    // executor family; the zero-online assertions rely on the window recipe
    // quiescing the resident chain runner before the suite starts (see PROGRESS).
    @BeforeEach
    @AfterEach
    void cleanExecutors() {
        AppautoExecutorFixture.deleteExecutorFamily(jdbcTemplate, "doctor-ex-");
    }

    
    private long registerWithCaps(String name, Map<String, Object> caps) {
        return executorService.register(new ExecutorRegisterInput(
                name, null, null, "web,app", "chromium", 2, "1.0.0", 0, caps));
    }

    private Map<String, Object> mobilewrightFullCaps() {
        return Map.of(
                "hostOs", "linux",
                "tools", Map.of("mobilecli", true, "adb", true, "maestro", false,
                        "appium", false, "hdc", false, "xcrun", false, "wx_devtools_cli", false),
                "runtimes", Map.of("node", "22.23.1", "python", "3.11.9", "java", "17.0.2"),
                "engines", List.of("MOBILEWRIGHT"),
                "platforms", List.of("ANDROID"),
                "deviceKinds", List.of("EMULATOR"));
    }

    private AppautoEngineHealthInfo find(List<AppautoEngineHealthInfo> rows, String code) {
        return rows.stream().filter(r -> code.equals(r.code())).findFirst().orElse(null);
    }

    @Test
    void executor_with_satisfied_machine_face_is_ready_with_empty_diff() {
        AppautoEngineHealthInfo before = find(envDoctorService.health(false), "MOBILEWRIGHT");
        assertNotNull(before, "启用面必须含默认引擎 Mobilewright");
        registerWithCaps("doctor-ex-full", mobilewrightFullCaps());
        List<AppautoEngineHealthInfo> rows = envDoctorService.health(false);
        AppautoEngineHealthInfo mw = find(rows, "MOBILEWRIGHT");
        assertNotNull(mw, "启用面必须含默认引擎 Mobilewright");
        assertTrue(mw.enabled());
        assertEquals(before.onlineExecutors() + 1, mw.onlineExecutors(), "本台入池=在线 +1");
        assertEquals(before.readyExecutors() + 1, mw.readyExecutors(),
                "node>=22.12+mobilecli 在位=就绪 +1: " + mw.blockingItems());
        assertTrue(mw.blockingItems().isEmpty(),
                "dev 单机前提=常驻台机器面满足 MOBILEWRIGHT，全满足不得挂差集项: " + mw.blockingItems());
        // seed default flipped to on by the AI default-on batch (2026-11-02 expansion): dev DB and schema-init both '1'
        assertTrue(mw.aiDoctorEnabled(), "ai.appauto.env-doctor 键出厂默认开（翻值批），关=仅确定性差集零 LLM 语义由实现恒纯保证");
    }

    @Test
    void runtime_below_descriptor_requirement_blocks_with_version_in_reason() {
        registerWithCaps("doctor-ex-oldnode", Map.of(
                "hostOs", "linux",
                "tools", Map.of("mobilecli", true, "adb", true),
                "runtimes", Map.of("node", "18.0.0"),
                "engines", List.of("MOBILEWRIGHT"),
                "platforms", List.of("ANDROID"),
                "deviceKinds", List.of("EMULATOR")));
        AppautoEngineHealthInfo mw = find(envDoctorService.health(false), "MOBILEWRIGHT");
        assertEquals(0, mw.readyExecutors(), "node 18 低于描述符 >=22.12=不满足");
        assertTrue(mw.blockingItems().stream()
                        .anyMatch(s -> s.contains("node") && s.contains("22.12")),
                "差集必须人话点名版本要求: " + mw.blockingItems());
    }

    @Test
    void missing_tool_from_env_check_spec_names_install_guidance() {
        registerWithCaps("doctor-ex-notool", Map.of(
                "hostOs", "linux",
                "tools", Map.of("mobilecli", false, "adb", true),
                "runtimes", Map.of("node", "22.23.1"),
                "engines", List.of(),
                "platforms", List.of("ANDROID"),
                "deviceKinds", List.of("EMULATOR")));
        AppautoEngineHealthInfo mw = find(envDoctorService.health(false), "MOBILEWRIGHT");
        assertEquals(0, mw.readyExecutors());
        assertTrue(mw.blockingItems().stream()
                        .anyMatch(s -> s.contains("mobilecli") && s.contains("安装")),
                "工具缺件必须人话点名装机指引（用户自装不随包分发）: " + mw.blockingItems());
    }

    @Test
    void unprobeable_check_entry_is_honest_coverage_gap() {
        registerWithCaps("doctor-ex-appium", Map.of(
                "hostOs", "linux",
                "tools", Map.of("appium", true, "adb", true),
                "runtimes", Map.of("node", "20.11.0"),
                "engines", List.of("APPIUM"),
                "platforms", List.of("ANDROID"),
                "deviceKinds", List.of("EMULATOR")));
        AppautoEngineHealthInfo appium = find(envDoctorService.health(false), "APPIUM");
        assertNotNull(appium);
        assertTrue(appium.blockingItems().stream().anyMatch(s -> s.contains("4723") || s.contains("端口")),
                "端口探针面=覆盖缺口必须点名: " + appium.blockingItems());
    }

    @Test
    void zero_online_executors_reports_honestly() {
        List<AppautoEngineHealthInfo> rows = envDoctorService.health(false);
        assertFalse(rows.isEmpty(), "目录启用面与执行器在线面解耦——零在线仍出全引擎清单");
        AppautoEngineHealthInfo first = rows.get(0);
        assertEquals(0, first.readyExecutors());
        assertTrue(first.blockingItems().stream().anyMatch(s -> s.contains("无在线执行器")),
                "零在线=人话点名「当前无在线执行器」: " + first.blockingItems());
    }

    @Test
    void disabled_engine_hidden_unless_include_disabled() {
        assertFalse(envDoctorService.health(false).stream()
                .anyMatch(r -> "HYPIUM".equals(r.code())), "停用候补位不得混入用户面");
        AppautoEngineHealthInfo hypium = find(envDoctorService.health(true), "HYPIUM");
        assertNotNull(hypium, "管理侧含停用候补位");
        assertFalse(hypium.enabled());
        assertEquals(0, hypium.readyExecutors());
        assertTrue(hypium.blockingItems().stream().anyMatch(s -> s.contains("停用") || s.contains("下架")),
                "停用行必须点名上下架: " + hypium.blockingItems());
    }

    @Test
    void ready_count_only_counts_satisfied_executors() {
        AppautoEngineHealthInfo before = find(envDoctorService.health(false), "MOBILEWRIGHT");
        assertNotNull(before);
        registerWithCaps("doctor-ex-ok", mobilewrightFullCaps());
        registerWithCaps("doctor-ex-bad", Map.of(
                "hostOs", "linux",
                "tools", Map.of("mobilecli", false),
                "runtimes", Map.of("node", "22.23.1"),
                "engines", List.of("MOBILEWRIGHT"),
                "platforms", List.of("ANDROID"),
                "deviceKinds", List.of("EMULATOR")));
        AppautoEngineHealthInfo mw = find(envDoctorService.health(false), "MOBILEWRIGHT");
        assertEquals(before.onlineExecutors() + 2, mw.onlineExecutors(), "本用例两台入池=在线 +2");
        assertEquals(before.readyExecutors() + 1, mw.readyExecutors(),
                "满足机器面=仅本用例 ok 台计入就绪，bad 台 mobilecli 缺件不计数");
    }
}
