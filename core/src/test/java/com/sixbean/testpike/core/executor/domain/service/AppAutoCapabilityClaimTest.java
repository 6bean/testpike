package com.sixbean.testpike.core.executor.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.executor.api.dto.ClaimTaskView;
import com.sixbean.testpike.core.executor.api.dto.ExecutorInfo;
import com.sixbean.testpike.core.executor.api.dto.ExecutorRegisterInput;
import com.sixbean.testpike.core.executor.domain.component.AppAutoDispatchSupport;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class AppAutoCapabilityClaimTest {

    
    private static final String BROWSER_ACTION =
            "{\"version\":\"v2\",\"steps\":[{\"action\":\"goto\",\"target\":{\"url\":\"http://demo.local\"}}]}";

    private static final String MOBILEWRIGHT_ENGINE =
            "{\"code\":\"MOBILEWRIGHT\",\"platform\":\"ANDROID\",\"deviceKind\":\"EMULATOR\"}";

    @Autowired
    private ExecTaskService execTaskService;
    @Autowired
    private ExecutorService executorService;
    @Autowired
    private AppAutoDispatchSupport appAutoDispatchSupport;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Fixture projects created by this test instance (swept by the FK-tree helper). */
    private final List<Long> projectIds = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Unique run token keeps fixture project codes apart on the shared dev db. */
    private final String runToken = SharedDbFixtureSweep.newToken();

    private long fixtureProject;

    @BeforeEach
    @AfterEach
    void cleanTables() {
        // Fixture-scoped teardown : sweep only this suite's naming
        // families — 'capclaim*' fixture projects with their task trees (keeps the
        // claim queue head free of crashed-run residue) and 'cap-ex-*' executors.
        // A blanket TRUNCATE of tp_exec_task/tp_executor would wipe rows owned by
        // concurrent suites on the shared dev database.
        SharedDbFixtureSweep.byProjectList(jdbcTemplate, jdbcTemplate.queryForList(
                "SELECT id FROM tp_project WHERE code LIKE 'capclaim%'", Long.class));
        jdbcTemplate.update("DELETE FROM tp_executor WHERE name LIKE 'cap-ex-%'");
        projectIds.clear();
        fixtureProject = 0L;
    }

    private String appAutoEnvelope(String engineJson) {
        return "{\"kind\":\"app-auto-run\",\"runId\":1,\"leaseRef\":\"r1\",\"engine\":" + engineJson
                + ",\"steps\":[{\"action\":\"app.launch\",\"target\":{\"pkg\":\"com.demo\"}}]}";
    }

    private long insertTask(int taskType, String actionJson) {
        if (fixtureProject == 0L) {
            fixtureProject = SharedDbFixtureSweep.createTrackedProject(
                    jdbcTemplate, "capclaim" + runToken, projectIds);
        }
        return jdbcTemplate.queryForObject(
                "INSERT INTO tp_exec_task (project_id, case_id, status, schema_version,"
                        + " action_json, task_type) VALUES (?, 0, 1, 'v2', ?, ?) RETURNING id",
                Long.class, fixtureProject, actionJson, taskType);
    }

    private long registerWithCaps(String name, Map<String, Object> caps) {
        return executorService.register(new ExecutorRegisterInput(
                name, null, null, "web,app", "chromium", 2, "1.0.0", 0, caps));
    }


    @Test
    void register_persists_capabilities_and_readback_carries_engine_face() {
        long id = registerWithCaps("cap-ex-1", Map.of(
                "hostOs", "linux", "engines", List.of("MOBILEWRIGHT"), "platforms", List.of("ANDROID")));
        ExecutorInfo info = executorService.get(id);
        assertNotNull(info.capabilities(), "读回必须带 capabilities 消费面");
        assertEquals(List.of("MOBILEWRIGHT"), info.capabilities().get("engines"));
        String raw = jdbcTemplate.queryForObject(
                "SELECT capabilities::text FROM tp_executor WHERE id = ?", String.class, id);
        assertTrue(raw.contains("MOBILEWRIGHT"), "列内必须是注册上报原文: " + raw);
    }

    @Test
    void register_update_path_refreshes_capabilities_on_same_name() {
        long first = registerWithCaps("cap-ex-2", Map.of("engines", List.of()));
        long second = registerWithCaps("cap-ex-2", Map.of("engines", List.of("MAESTRO")));
        assertEquals(first, second, "同名注册必须保持同一 id（03 协议 2.1）");
        ExecutorInfo info = executorService.get(first);
        assertEquals(List.of("MAESTRO"), info.capabilities().get("engines"),
                "UPDATE 路必须全量刷新能力面（重装即刷新）");
    }

    @Test
    void legacy_eight_arg_register_falls_back_to_empty_capabilities_honestly() {
        long id = executorService.register(new ExecutorRegisterInput(
                "cap-ex-legacy", null, null, "web", "chromium", 2, "0.9.0", 0));
        ExecutorInfo info = executorService.get(id);
        assertTrue(info.capabilities() == null || info.capabilities().isEmpty(),
                "旧执行器未上报=空能力面诚实缺席（禁猜能力）");
    }


    @Test
    void claim_dispatches_app_task_to_capable_executor() {
        long exId = registerWithCaps("cap-ex-ok", Map.of(
                "engines", List.of("MOBILEWRIGHT"), "platforms", List.of("ANDROID"),
                "deviceKinds", List.of("EMULATOR")));
        long taskId = insertTask(6, appAutoEnvelope(MOBILEWRIGHT_ENGINE));
        ClaimTaskView view = execTaskService.claim(exId);
        assertNotNull(view, "能力对账通过的 APP 任务必须可领");
        assertEquals(taskId, view.taskId());
    }

    @Test
    void claim_skips_app_task_when_executor_lacks_engine_and_keeps_task_queued() {
        long exId = registerWithCaps("cap-ex-no-engine", Map.of(
                "engines", List.of("MAESTRO"), "platforms", List.of("ANDROID"),
                "deviceKinds", List.of("EMULATOR")));
        long taskId = insertTask(6, appAutoEnvelope(MOBILEWRIGHT_ENGINE));
        assertNull(execTaskService.claim(exId), "无该引擎能力的执行器对该候选必须跳过排队（禁异常）");
        Integer status = jdbcTemplate.queryForObject(
                "SELECT status FROM tp_exec_task WHERE id = ?", Integer.class, taskId);
        assertEquals(1, status, "被跳过的 APP 任务必须滞留待派发（排队非失败）");
    }

    @Test
    void claim_skips_app_task_when_executor_lacks_platform_or_device_kind() {
        long exIosOnly = registerWithCaps("cap-ex-ios", Map.of(
                "engines", List.of("MOBILEWRIGHT"), "platforms", List.of("IOS"),
                "deviceKinds", List.of("SIMULATOR")));
        insertTask(6, appAutoEnvelope(MOBILEWRIGHT_ENGINE));
        assertNull(execTaskService.claim(exIosOnly), "平台维不合=跳过");

        long exNoDeviceKind = registerWithCaps("cap-ex-nokind", Map.of(
                "engines", List.of("MOBILEWRIGHT"), "platforms", List.of("ANDROID"),
                "deviceKinds", List.of()));
        assertNull(execTaskService.claim(exNoDeviceKind), "设备型维不合=跳过");
    }

    @Test
    void claim_skips_app_task_for_executor_without_capabilities_report() {
        long exId = executorService.register(new ExecutorRegisterInput(
                "cap-ex-blind", null, null, "web,app", "chromium", 2, "0.9.0", 0));
        insertTask(6, appAutoEnvelope(MOBILEWRIGHT_ENGINE));
        assertNull(execTaskService.claim(exId),
                "未上报能力面的旧执行器对 APP 任务=能力未知禁派发（谎报红线）");
    }

    @Test
    void claim_still_dispatches_browser_task_to_executor_without_capabilities() {
        long exId = executorService.register(new ExecutorRegisterInput(
                "cap-ex-web", null, null, "web", "chromium", 2, "0.9.0", 0));
        long taskId = insertTask(1, BROWSER_ACTION);
        ClaimTaskView view = execTaskService.claim(exId);
        assertNotNull(view, "非 APP 任务恒放行=旧执行器零破坏");
        assertEquals(taskId, view.taskId());
    }

    @Test
    void claim_prefers_capable_browser_candidate_when_app_task_is_queued() {
        long exId = registerWithCaps("cap-ex-mixed", Map.of(
                "engines", List.of("MAESTRO"), "platforms", List.of("ANDROID")));
        long appTask = insertTask(6, appAutoEnvelope(MOBILEWRIGHT_ENGINE));
        long webTask = insertTask(1, BROWSER_ACTION);
        ClaimTaskView view = execTaskService.claim(exId);
        assertNotNull(view, "APP 候选跳过后必须继续判后续候选（循环级跳过非整轮停摆）");
        assertEquals(webTask, view.taskId());
        assertEquals(1, jdbcTemplate.queryForObject(
                        "SELECT status FROM tp_exec_task WHERE id = ?", Integer.class, appTask),
                "APP 任务保持排队");
    }


    @Test
    void explain_no_capable_executor_is_plain_words_with_engine_and_count() {
        registerWithCaps("cap-ex-pre", Map.of("engines", List.of("MAESTRO")));
        // Deterministic no-match dims: HARMONY is reported by no executor in the
        // shared pool (live runner machines included), so the precheck tests the
        // capability-matching face instead of racing a heartbeat-refreshed pool —
        // the old blanket TRUNCATE only hid the runner for one beat (ratchet note).
        String harmonyDims =
                "{\"code\":\"MOBILEWRIGHT\",\"platform\":\"HARMONY\",\"deviceKind\":\"EMULATOR\"}";
        long taskId = insertTask(6, appAutoEnvelope(harmonyDims));
        assertFalse(appAutoDispatchSupport.hasCapableExecutor(taskId),
                "在线且具备该引擎×平台能力的执行器数=0 → 预检判无匹配");
        String explain = appAutoDispatchSupport.explainNoCapableExecutor(taskId);
        assertTrue(explain.contains("Mobilewright") || explain.contains("MOBILEWRIGHT"),
                "人话必须点名所需引擎: " + explain);
        assertTrue(explain.contains("0"), "人话必须带当前台数: " + explain);
        assertFalse(explain.contains("{"), "人话禁 JSON dump");
    }

    @Test
    void non_app_task_precheck_always_passes() {
        long taskId = insertTask(1, BROWSER_ACTION);
        assertTrue(appAutoDispatchSupport.hasCapableExecutor(taskId),
                "非 APP 任务预检恒放行（旧执行器零破坏）");
    }
}
