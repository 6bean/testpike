package com.sixbean.testpike.core.appauto.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.appauto.api.dto.AppautoDeviceInput;
import com.sixbean.testpike.core.appauto.api.dto.AppautoMatrixCombo;
import com.sixbean.testpike.core.appauto.api.dto.AppautoMatrixDispatchResult;
import com.sixbean.testpike.core.appauto.api.dto.AppautoMatrixFanoutInput;
import com.sixbean.testpike.core.appauto.api.dto.AppautoMatrixModelRow;
import com.sixbean.testpike.core.appauto.api.dto.AppautoMatrixPrecheckView;
import com.sixbean.testpike.core.appauto.api.dto.AppautoMatrixStepFailRow;
import com.sixbean.testpike.core.appauto.domain.service.AppautoDeviceService;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import com.sixbean.testpike.core.executor.domain.service.ExecTaskService;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.api.dto.ProjectEnvInput;
import com.sixbean.testpike.core.project.domain.service.ProjectEnvService;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.script.api.dto.ScriptSaveInput;
import com.sixbean.testpike.core.script.domain.service.ScriptService;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <p>Real dev-database SpringBootTest (no mocks); fixture teardown deletes
 * only rows keyed by the run's own projects. Engine catalog rows come from the
 * manually applied migration seeds. Envelope device addressing (leaseRef) is
 * wired by a later segment and is not invented here.
 */
@SpringBootTest(classes = CoreApplication.class)
class AppautoMatrixServiceTest {

    private static final String APP_ACTION_JSON =
            "{\"version\":\"v2\",\"steps\":[{\"action\":\"app.launch\",\"target\":{\"pkg\":\"com.demo\"}},"
                    + "{\"action\":\"app.tap\",\"target\":{\"resourceId\":\"com.demo:id/btn\"}}]}";
    private static final String WEB_ACTION_JSON =
            "{\"version\":\"v2\",\"steps\":[{\"action\":\"goto\",\"target\":{\"url\":\"http://demo.local\"}}]}";

    private static final String KEY_FANOUT_MAX = "appauto.matrix-fanout-max";

    @Autowired
    private AppautoMatrixService matrixService;
    @Autowired
    private ExecTaskService execTaskService;
    @Autowired
    private AppautoDeviceService deviceService;
    @Autowired
    private CaseService caseService;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private ProjectEnvService projectEnvService;
    @Autowired
    private ScriptService scriptService;
    @Autowired
    private ConfigCacheHolder configCacheHolder;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private com.sixbean.testpike.core.executor.domain.service.ExecutorService executorService;

    private long projectId;
    private long envId;
    private long caseId;
    private final List<Long> fixtureProjectIds = new ArrayList<>();

    @BeforeEach
    void cleanTables() {
        // Fixture-scoped teardown (ratchet): the blanket TRUNCATE wiped other
        // suites'/chains' batches, cases and device rows on the shared dev
        // database. Deletes are now keyed by the previous run's fixture
        // projects; tp_batch_case carries neither project_id nor a FK, so it
        // is cleared through its batches before the sweep removes them.
        sweepTrackedFixtureProjects();
        configCacheHolder.remove(KEY_FANOUT_MAX);
        projectId = projectService.create(1L, new ProjectCreateInput("矩阵扇出测试", "MTX"
                + System.nanoTime(), "zh-CN", "P1", null));
        fixtureProjectIds.add(projectId);
        envId = projectEnvService.create(projectId, new ProjectEnvInput(
                "测试环境", 2, "http://demo.local", null, "web,mobile", "chromium", "v1.0.0"));
    }

    /** Delete only the rows the tracked fixture projects own (idempotent). */
    private void sweepTrackedFixtureProjects() {
        for (Long pid : fixtureProjectIds) {
            jdbcTemplate.update("DELETE FROM tp_batch_case WHERE batch_id IN "
                    + "(SELECT id FROM tp_exec_batch WHERE project_id = ?)", pid);
            SharedDbFixtureSweep.byProject(jdbcTemplate, pid);
        }
        fixtureProjectIds.clear();
    }

    @AfterEach
    void sweepFixtureProject() {
        sweepTrackedFixtureProjects();
    }

    @AfterEach
    void dropFixtureExecutor() {
        jdbcTemplate.update("DELETE FROM tp_executor WHERE name LIKE 'mtx-ex-%'");
    }

    
    @BeforeEach
    void seedCapableExecutor() {
        jdbcTemplate.update("DELETE FROM tp_executor WHERE name LIKE 'mtx-ex-%'");
        executorService.register(new com.sixbean.testpike.core.executor.api.dto.ExecutorRegisterInput(
                "mtx-ex-" + System.nanoTime(), null, null, "web,app", "chromium", 2, "1.0.0", 0,
                Map.of("engines", List.of("MOBILEWRIGHT", "APPIUM", "MAESTRO", "XCUITEST"),
                        "platforms", List.of("ANDROID", "IOS"),
                        "deviceKinds", List.of("EMULATOR", "REAL", "SIMULATOR"))));
    }

    
    private long appCase(String title, String engine, String actionJson) {
        long id = caseService.create(1L, new CaseCreateInput(projectId, title, "前置", "预期",
                1, 2, "mobile", 3, 2, 1, null, List.of(), null, null,
                "app", engine, null)).id();
        if (actionJson != null) {
            scriptService.save(new ScriptSaveInput(id, "v2", actionJson, 2));
        }
        return id;
    }

    private AppautoMatrixFanoutInput input(List<AppautoMatrixCombo> combos) {
        return new AppautoMatrixFanoutInput(projectId, caseId, "机型矩阵批", envId, combos);
    }

    private AppautoMatrixCombo combo(String engine, String platform, String deviceKind) {
        return new AppautoMatrixCombo(engine, platform, deviceKind, null);
    }

    @Test
    void fanout_两组合_同批两用例单行两任务各带自引擎信封() {
        caseId = appCase("登录页点击", "MOBILEWRIGHT", APP_ACTION_JSON);

        AppautoMatrixDispatchResult result = matrixService.fanout(1L, input(List.of(
                combo("MOBILEWRIGHT", "ANDROID", "EMULATOR"),
                combo("APPIUM", "IOS", "REAL"))));

        assertEquals(2, result.createdTasks(), "两组合=两执行任务（复用批次派发支，禁新调度）");
        assertEquals(2, result.taskIds().size());
        assertNotNull(result.batchNo(), "扇出自建草稿批次号回显");
        assertTrue(result.message().contains("2"), "人话计数进 message: " + result.message());
        Integer memberRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_batch_case WHERE batch_id = ?", Integer.class, result.batchId());
        assertEquals(1, memberRows, "tp_batch_case (batch_id,case_id) 唯一=扇出批单行多任务禁双源");
        Integer batchStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM tp_exec_batch WHERE id = ?", Integer.class, result.batchId());
        assertEquals(2, batchStatus, "扇出即入待执行队列（与批次 start 同态）");
        List<Map<String, Object>> envelopes = jdbcTemplate.queryForList(
                "SELECT case_id, task_type, action_json::text AS aj FROM tp_exec_task WHERE batch_id = ?"
                        + " ORDER BY id", result.batchId());
        assertEquals(2, envelopes.size());
        for (Map<String, Object> row : envelopes) {
            assertEquals(6, ((Number) row.get("task_type")).intValue(), "APP 支 task_type=6 分流");
            assertEquals(caseId, ((Number) row.get("case_id")).longValue(), "扇出任务带真实用例 id");
            String aj = (String) row.get("aj");
            assertTrue(aj.contains("app-auto-run"), "信封 kind=app-auto-run: " + aj);
            assertTrue(aj.contains("app.launch"), "步序原样透传禁改写");
        }
        String first = (String) envelopes.get(0).get("aj");
        String second = (String) envelopes.get(1).get("aj");
        assertTrue(first.contains("MOBILEWRIGHT") && second.contains("APPIUM"),
                "逐组合各自引擎描述符投影（扇出=同用例不同引擎/平台组合）");
        assertFalse(first.equals(second), "两组合信封不得同形");
    }


    
    private long device(String name, String serial, String deviceType, String adbAddress) {
        long id = deviceService.register(projectId, new AppautoDeviceInput(name, serial, deviceType,
                adbAddress, "Pixel-7", "13", "1080x2400", 420, List.of()));
        deviceService.recordProbeResult(id, true);
        return id;
    }

    @Test
    void fanout_命中设备档案_信封带leaseRef寻址_无档案平台诚实不装() {
        String serial = "mtx-emu-" + System.nanoTime();
        long deviceId = device("矩阵模拟器", serial, "EMULATOR", null);
        caseId = appCase("登录页点击", "MOBILEWRIGHT", APP_ACTION_JSON);

        AppautoMatrixDispatchResult result = matrixService.fanout(1L, input(List.of(
                combo("MOBILEWRIGHT", "ANDROID", "EMULATOR"),
                combo("XCUITEST", "IOS", "SIMULATOR"))));
        assertEquals(2, result.createdTasks());

        List<String> envelopes = jdbcTemplate.queryForList(
                "SELECT action_json::text FROM tp_exec_task WHERE batch_id = ? ORDER BY id",
                String.class, result.batchId());
        String androidEnv = envelopes.get(0);
        assertTrue(androidEnv.contains("\"leaseRef\""),
                "命中档案=信封必带设备寻址键（T298b claim 设备占用闸消费面）: " + androidEnv)
;
        Map<String, Object> ref = readLeaseRef(androidEnv);
        assertEquals(serial, ref.get("serial"), "leaseRef.serial=档案 serial 原样（activeHolder 寻址源）");
        assertEquals(deviceId, ((Number) ref.get("deviceId")).longValue(),
                "leaseRef.deviceId=core 档案行 id 权威（禁按 serial 猜）");
        assertFalse(envelopes.get(1).contains("\"leaseRef\""),
                "IOS SIMULATOR 谱无档案=信封无 leaseRef（排队闸自然不加，禁编造寻址）");
    }

    
    private static Map<String, Object> readLeaseRef(String actionJson) {
        try {
            Map<String, Object> env = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(actionJson, Map.class);
            Object ref = env.get("leaseRef");
            assertTrue(ref instanceof Map, "leaseRef 必须 dict 形态 {deviceId,serial}（executor 契约）");
            return (Map<String, Object>) ref;
        } catch (Exception e) {
            throw new AssertionError("信封回解析失败: " + e.getMessage(), e);
        }
    }

    @Test
    void fanout_两谱映射_ADB与USB档案命中真机组合_模拟器组合不吃ADB() {
        String emuSerial = "mtx-emu2-" + System.nanoTime();
        String adbSerial = "mtx-adb-" + System.nanoTime();
        device("本地模拟器", emuSerial, "EMULATOR", null);
        device("远程真机", adbSerial, "ADB", "192.168.1.30:5555");
        caseId = appCase("登录页点击", "MOBILEWRIGHT", APP_ACTION_JSON);

        AppautoMatrixDispatchResult result = matrixService.fanout(1L, input(List.of(
                combo("APPIUM", "ANDROID", "REAL"))));
        String aj = jdbcTemplate.queryForObject(
                "SELECT action_json::text FROM tp_exec_task WHERE batch_id = ?",
                String.class, result.batchId());
        assertTrue(aj != null && aj.contains(adbSerial),
                "device_type(ADB)→deviceKind(REAL) 映射闸：真机组合须寻址 ADB 档案: " + aj);

        AppautoMatrixDispatchResult emuOnly = matrixService.fanout(1L, input(List.of(
                combo("MOBILEWRIGHT", "ANDROID", "EMULATOR"))));
        String emuAj = jdbcTemplate.queryForObject(
                "SELECT action_json::text FROM tp_exec_task WHERE batch_id = ?",
                String.class, emuOnly.batchId());
        assertTrue(emuAj != null && emuAj.contains(emuSerial) && !emuAj.contains(adbSerial),
                "模拟器组合只吃 EMULATOR 档案（禁字面直配/禁跨谱误占真机）: " + emuAj);
    }

    @Test
    void fanout_隔离设备不参与寻址_全部隔离时信封无leaseRef() {
        String serial = "mtx-q-" + System.nanoTime();
        long d1 = device("隔离机", serial, "EMULATOR", null);
        jdbcTemplate.update("UPDATE tp_appauto_device SET health_state = 'QUARANTINED' WHERE id = ?",
                d1);
        caseId = appCase("登录页点击", "MOBILEWRIGHT", APP_ACTION_JSON);

        // Pitfall: the resident runner registers shared-slot EMULATOR machines this test does
        // not own, so the bare "no leaseRef at all" assertion drifts with runner state. The
        // intent under test is that a quarantined machine is never selected: assert the
        // fixture machine stays out of the envelope, and pin the empty-envelope case
        // deterministically through a label group that owns no machine (no fallback to
        // shared-slot rows either).
        AppautoMatrixDispatchResult result = matrixService.fanout(1L, input(List.of(
                combo("MOBILEWRIGHT", "ANDROID", "EMULATOR"))));
        String aj = jdbcTemplate.queryForObject(
                "SELECT action_json::text FROM tp_exec_task WHERE batch_id = ?",
                String.class, result.batchId());
        assertFalse(aj != null && aj.contains("\"" + serial + "\""),
                "隔离=人工决策面，扇出禁主动打隔离机（夹具机绝不被寻址）: " + aj);

        AppautoMatrixDispatchResult emptyGroup = matrixService.fanout(1L,
                new AppautoMatrixFanoutInput(projectId, caseId, "机型矩阵圈组批", envId,
                        List.of(combo("MOBILEWRIGHT", "ANDROID", "EMULATOR")), null,
                        "grp-none-" + System.nanoTime()));
        String egAj = jdbcTemplate.queryForObject(
                "SELECT action_json::text FROM tp_exec_task WHERE batch_id = ?",
                String.class, emptyGroup.batchId());
        assertFalse(egAj != null && egAj.contains("leaseRef"),
                "组内无可寻址档案=信封不装 leaseRef（存量无设备闸语义，禁借组外机）: " + egAj);
    }


    
    private long deviceWithModel(String name, String serial, String model, String osVersion) {
        long id = deviceService.register(projectId, new AppautoDeviceInput(name, serial, "EMULATOR",
                null, model, osVersion, "1080x2400", 420, List.of()));
        deviceService.recordProbeResult(id, true);
        return id;
    }

    @Test
    void modelReport_机型失败率聚合_人话差异行点名失败步() {
        long d1 = deviceWithModel("矩阵机A", "mtx-a-" + System.nanoTime(), "M-P10", "12");
        long d2 = deviceWithModel("矩阵机B", "mtx-b-" + System.nanoTime(), "Mi13", "14");
        caseId = appCase("登录页点击", "MOBILEWRIGHT", APP_ACTION_JSON);

        AppautoMatrixDispatchResult result = matrixService.fanout(1L, input(List.of(
                combo("MOBILEWRIGHT", "ANDROID", "EMULATOR"),
                combo("MAESTRO", "ANDROID", "EMULATOR"))));
        List<Long> taskIds = result.taskIds();
        assertEquals(2, taskIds.size());
        String passTimeline = "[{\"stepNo\":1,\"action\":\"app.launch\",\"result\":\"pass\",\"durationMs\":320},"
                + "{\"stepNo\":2,\"action\":\"app.tap\",\"result\":\"pass\",\"durationMs\":150}]";
        String failTimeline = "[{\"stepNo\":1,\"action\":\"app.launch\",\"result\":\"pass\",\"durationMs\":300},"
                + "{\"stepNo\":2,\"action\":\"app.tap\",\"result\":\"fail\",\"durationMs\":900,"
                + "\"detail\":\"弹窗拦截：隐私协议未处理\"}]";
        jdbcTemplate.update("UPDATE tp_exec_task SET status = 3, steps_timeline = ?::jsonb,"
                + " finished_at = now() WHERE id = ?", passTimeline, taskIds.get(0));
        jdbcTemplate.update("UPDATE tp_exec_task SET status = 4, steps_timeline = ?::jsonb,"
                + " finished_at = now() WHERE id = ?", failTimeline, taskIds.get(1));

        List<AppautoMatrixModelRow> rows = matrixService.modelReport(projectId, caseId);
        assertEquals(2, rows.size(), "两台机型档案=两行（model×os_version 机型轴）");
        AppautoMatrixModelRow pass = rows.stream()
                .filter(r -> "M-P10".equals(r.model())).findFirst().orElseThrow();
        assertEquals(1, pass.samples());
        assertEquals(1, pass.passSamples());
        assertEquals(0, pass.failSamples());
        assertEquals(1.0, pass.passRate(), 0.0001);
        assertTrue(pass.message().contains("全部通过"), "全过机型人话行: " + pass.message());
        assertTrue(pass.stepFailures().isEmpty(), "全过=零失败步");

        AppautoMatrixModelRow fail = rows.stream()
                .filter(r -> "Mi13".equals(r.model())).findFirst().orElseThrow();
        assertEquals(1, fail.samples());
        assertEquals(0, fail.passSamples());
        assertEquals(0.0, fail.passRate(), 0.0001);
        assertEquals(1, fail.stepFailures().size(), "失败步聚合一行（stepNo=2 app.tap）");
        AppautoMatrixStepFailRow step = fail.stepFailures().get(0);
        assertEquals(2, step.stepNo());
        assertEquals("app.tap", step.action());
        assertEquals(1, step.failCount());
        assertTrue(step.sampleDetail().contains("弹窗拦截"), "人话归因样例进差异行: " + step.sampleDetail());
        assertTrue(fail.message().contains("第 2 步") && fail.message().contains("弹窗拦截"),
                "差异行人话=失败集中在第 N 步+归因: " + fail.message());
    }

    @Test
    void modelReport_窗口外与无设备寻址样本诚实排除() {
        deviceWithModel("矩阵机C", "mtx-c-" + System.nanoTime(), "G100", "13");
        caseId = appCase("登录页点击", "MOBILEWRIGHT", APP_ACTION_JSON);
        AppautoMatrixDispatchResult result = matrixService.fanout(1L,
                input(List.of(combo("MOBILEWRIGHT", "ANDROID", "EMULATOR"))));
        jdbcTemplate.update("UPDATE tp_exec_task SET status = 4, finished_at = now() - interval '30 days' "
                + "WHERE id = ?", result.taskIds().get(0));
        long noDeviceTask = execTaskService.createAppAutoRun(projectId, caseId, null,
                "{\"kind\":\"app-auto-run\",\"engine\":{\"code\":\"MOBILEWRIGHT\",\"platform\":\"ANDROID\"},"
                        + "\"steps\":[{\"action\":\"app.launch\"}]}");
        jdbcTemplate.update("UPDATE tp_exec_task SET status = 4, finished_at = now() WHERE id = ?",
                noDeviceTask);

        List<AppautoMatrixModelRow> rows = matrixService.modelReport(projectId, caseId);
        assertTrue(rows.isEmpty(),
                "窗口外样本（U6 窗口同源键）与无设备寻址样本都不得计入机型行: " + rows);
    }

    @Test
    void fanout_同型两台档案_组合轮转分配不撞同一台() {
        String s1 = "mtx-r1-" + System.nanoTime();
        String s2 = "mtx-r2-" + System.nanoTime();
        device("模拟器A", s1, "EMULATOR", null);
        device("模拟器B", s2, "EMULATOR", null);
        caseId = appCase("登录页点击", "MOBILEWRIGHT", APP_ACTION_JSON);

        AppautoMatrixDispatchResult result = matrixService.fanout(1L, input(List.of(
                combo("MOBILEWRIGHT", "ANDROID", "EMULATOR"),
                combo("MAESTRO", "ANDROID", "EMULATOR"),
                combo("APPIUM", "ANDROID", "EMULATOR"))));
        List<String> ajs = jdbcTemplate.queryForList(
                "SELECT action_json::text FROM tp_exec_task WHERE batch_id = ? ORDER BY id",
                String.class, result.batchId());
        assertEquals(3, ajs.size());
        assertTrue(ajs.get(0).contains(s1) && ajs.get(1).contains(s2) && ajs.get(2).contains(s1),
                "同型多台=轮转分配（扇出并行语义；排队期漂移由 activeHolder 闸兜）");
    }

    @Test
    void precheck_台数与批次估时同源_纯读零写库() {
        caseId = appCase("登录页点击", "MOBILEWRIGHT", APP_ACTION_JSON);

        AppautoMatrixPrecheckView view = matrixService.precheck(input(List.of(
                combo("MOBILEWRIGHT", "ANDROID", "EMULATOR"),
                combo("MAESTRO", "ANDROID", "REAL"),
                combo("APPIUM", "IOS", "REAL"))));

        assertEquals(3, view.fanoutTasks(), "预检台数=组合数");
        assertTrue(view.minutesPerTask() > 0, "每任务分钟数>0（与批次排队估时同源件，禁硬编码二套）");
        assertNotNull(view.estimatedStartAt(), "G5=排队至 X 时刻人话回显");
        assertTrue(view.message().contains("3"), "「预计 3 台×M 分钟」人话进 message: " + view.message());
        assertEquals(12, view.fanoutMaxAllowed(), "上限键缺省投影=12（S2 微裁默认，冻结文档 §14.6 G5）");
        // The zero-write contract is anchored on THIS run's fixture project:
        // a global count only reads clean when the whole table was emptied,
        // which never holds on the shared dev database.
        Integer batches = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_exec_batch WHERE project_id = ?", Integer.class, projectId);
        Integer tasks = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_exec_task WHERE project_id = ?", Integer.class, projectId);
        assertEquals(0, batches, "预检纯读零写库（本运行项目切片）");
        assertEquals(0, tasks, "预检纯读零写库（本运行项目切片）");
    }

    @Test
    void fanout_组合数超上限键_人话拒零写库() {
        caseId = appCase("登录页点击", "MOBILEWRIGHT", APP_ACTION_JSON);
        configCacheHolder.put(KEY_FANOUT_MAX, "2");

        AppautoMatrixFanoutInput over = input(List.of(
                combo("MOBILEWRIGHT", "ANDROID", "EMULATOR"),
                combo("MAESTRO", "ANDROID", "EMULATOR"),
                combo("APPIUM", "ANDROID", "EMULATOR")));
        ServiceException ex = assertThrows(ServiceException.class, () -> matrixService.fanout(1L, over));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("2"), "人话点名当前上限: " + ex.getMessage());
        // Zero-write anchored on this run's project (shared-dev tables keep other rows).
        assertEquals(0, (int) jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM tp_exec_task WHERE project_id = ?", Integer.class, projectId),
                "超上限拒单零写库（防无意识烧穿设备池）");

        configCacheHolder.put(KEY_FANOUT_MAX, "3");
        AppautoMatrixDispatchResult ok = matrixService.fanout(1L, over);
        assertEquals(3, ok.createdTasks(), "键热更放宽后扇出生效，禁重启禁二级缓存");
    }

    @Test
    void fanout_组合越出引擎声明面_人话拒() {
        caseId = appCase("登录页点击", "MOBILEWRIGHT", APP_ACTION_JSON);

        ServiceException badPlatform = assertThrows(ServiceException.class,
                () -> matrixService.precheck(input(List.of(combo("MAESTRO", "MP", "EMULATOR")))));
        assertTrue(badPlatform.getMessage().contains("MAESTRO"), "点名引擎+越面维度: " + badPlatform.getMessage());

        ServiceException badKind = assertThrows(ServiceException.class,
                () -> matrixService.precheck(input(List.of(combo("MOBILEWRIGHT", "ANDROID", "DEVTOOLS_IDE")))));
        assertTrue(badKind.getMessage().contains("DEVTOOLS_IDE"), "点名越面设备类: " + badKind.getMessage());
        // Zero-dispatch anchored on this run's project (shared-dev tables keep other rows).
        assertEquals(0, (int) jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM tp_exec_task WHERE project_id = ?", Integer.class, projectId),
                "越面组合零派发（不烧执行器会话）");
    }

    @Test
    void fanout_停用引擎与未知引擎_人话拒() {
        caseId = appCase("登录页点击", "MOBILEWRIGHT", APP_ACTION_JSON);

        ServiceException delisted = assertThrows(ServiceException.class,
                () -> matrixService.fanout(1L, input(List.of(combo("HYPIUM", "HARMONY_NEXT", "REAL")))));
        assertTrue(delisted.getMessage().contains("HYPIUM"), "下架引擎点名: " + delisted.getMessage());

        ServiceException unknown = assertThrows(ServiceException.class,
                () -> matrixService.fanout(1L, input(List.of(combo("AIRTEST", "ANDROID", "REAL")))));
        assertEquals(BizErrorCode.VALIDATION_ERROR, unknown.getCode(),
                "未知引擎（10-01 裁决不入矩阵）人话拒，禁静默回退默认引擎");
    }

    @Test
    void fanout_未挂目标或无app步骤或越项目_人话拒() {
        long webTargetCase = caseService.create(1L, new CaseCreateInput(projectId, "网页用例", "前置",
                "预期", 1, 2, "web", 3, 2, 1, null, List.of())).id();
        scriptService.save(new ScriptSaveInput(webTargetCase, "v2", WEB_ACTION_JSON, 2));
        caseId = webTargetCase;
        ServiceException notApp = assertThrows(ServiceException.class,
                () -> matrixService.fanout(1L, input(List.of(combo("MOBILEWRIGHT", "ANDROID", "EMULATOR")))));
        assertTrue(notApp.getMessage().contains("自动化目标"), "未挂 app/mp 目标=人话拒: " + notApp.getMessage());

        long noScriptCase = appCase("未编排步骤", "MOBILEWRIGHT", null);
        caseId = noScriptCase;
        ServiceException noSteps = assertThrows(ServiceException.class,
                () -> matrixService.fanout(1L, input(List.of(combo("MOBILEWRIGHT", "ANDROID", "EMULATOR")))));
        assertTrue(noSteps.getMessage().contains("app."), "未编排 app.* 步骤=人话拒禁烧会话: " + noSteps.getMessage());

        long foreignProject = projectService.create(1L, new ProjectCreateInput("他项目", "MTXF"
                + System.nanoTime(), "zh-CN", "P1", null));
        long foreignCase = caseService.create(1L, new CaseCreateInput(foreignProject, "越界用例", "前置",
                "预期", 1, 2, "mobile", 3, 2, 1, null, List.of(), null, null, "app", "MOBILEWRIGHT", null)).id();
        ServiceException crossProject = assertThrows(ServiceException.class,
                () -> matrixService.fanout(1L, new AppautoMatrixFanoutInput(projectId, foreignCase,
                        "机型矩阵批", envId, List.of(combo("MOBILEWRIGHT", "ANDROID", "EMULATOR")))));
        assertEquals(BizErrorCode.VALIDATION_ERROR, crossProject.getCode(), "H1 跨项目拒");
    }

    @Test
    void fanout_空组合与重复组合_去重计数与人话拒() {
        caseId = appCase("登录页点击", "MOBILEWRIGHT", APP_ACTION_JSON);

        ServiceException empty = assertThrows(ServiceException.class,
                () -> matrixService.fanout(1L, input(List.of())));
        assertTrue(empty.getMessage().contains("组合"), "空组合=人话拒: " + empty.getMessage());

        AppautoMatrixDispatchResult result = matrixService.fanout(1L, input(List.of(
                combo("MOBILEWRIGHT", "ANDROID", "EMULATOR"),
                combo("MOBILEWRIGHT", "ANDROID", "EMULATOR"),
                combo("MAESTRO", "ANDROID", "EMULATOR"))));
        assertEquals(2, result.createdTasks(), "同引擎×平台×设备类重复组合去重后派发（禁重复烧机时）");
    }


    @Autowired
    private com.sixbean.testpike.core.appauto.domain.service.AppautoReservationService reservationService;

    private long holderUserId() {
        return jdbcTemplate.queryForObject("SELECT id FROM tp_user ORDER BY id LIMIT 1", Long.class);
    }

    
    private long reservation(long deviceIdArg, java.time.LocalDateTime starts,
                             java.time.LocalDateTime ends) {
        long holder = holderUserId();
        var view = reservationService.create(new com.sixbean.testpike.core.appauto.api.dto
                .AppautoReservationInput(projectId, deviceIdArg, null, holder, starts, ends, "矩阵咬合演练"));
        if ("PENDING_APPROVAL".equals(view.state())) {
            view = reservationService.approve(view.id(), holder);
        }
        assertEquals("ACTIVE", view.state(), "预约夹具须落 ACTIVE 才可派发咬合");
        return view.id();
    }

    private AppautoMatrixFanoutInput inputWithReservation(List<AppautoMatrixCombo> combos, Long reservationId) {
        return new AppautoMatrixFanoutInput(projectId, caseId, "预约矩阵批", envId, combos, reservationId);
    }

    @Test
    void fanout_绑预约_选机限预约机_两组合同落预约机不轮转漂出() {
        String s1 = "mtx-rsv-a-" + System.nanoTime();
        String s2 = "mtx-rsv-b-" + System.nanoTime();
        long d1 = device("非预约机", s1, "EMULATOR", null);
        long d2 = device("预约机", s2, "EMULATOR", null);
        assertTrue(d1 != d2);
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        long rid = reservation(d2, now.minusHours(1), now.plusHours(2));
        caseId = appCase("登录页点击", "MOBILEWRIGHT", APP_ACTION_JSON);

        AppautoMatrixDispatchResult result = matrixService.fanout(1L, inputWithReservation(List.of(
                combo("MOBILEWRIGHT", "ANDROID", "EMULATOR"),
                combo("MAESTRO", "ANDROID", "EMULATOR")), rid));
        List<String> ajs = jdbcTemplate.queryForList(
                "SELECT action_json::text FROM tp_exec_task WHERE batch_id = ? ORDER BY id",
                String.class, result.batchId());
        assertEquals(2, ajs.size());
        for (String aj : ajs) {
            Map<String, Object> ref = readLeaseRef(aj);
            assertEquals(d2, ((Number) ref.get("deviceId")).longValue(),
                    "绑定预约=选机限预约窗内机（轮转不得漂出预约机）: " + aj);
            assertEquals(s2, ref.get("serial"), "leaseRef.serial=预约机档案 serial");
        }
    }

    @Test
    void fanout_预约窗已过_信封不装leaseRef_与无预约语义同形非故障() {
        String s = "mtx-rsv-x-" + System.nanoTime();
        long d1 = device("超窗预约机", s, "EMULATOR", null);
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        long rid = reservation(d1, now.minusHours(3), now.minusMinutes(10));
        caseId = appCase("登录页点击", "MOBILEWRIGHT", APP_ACTION_JSON);

        AppautoMatrixDispatchResult result = matrixService.fanout(1L, inputWithReservation(
                List.of(combo("MOBILEWRIGHT", "ANDROID", "EMULATOR")), rid));
        String aj = jdbcTemplate.queryForObject(
                "SELECT action_json::text FROM tp_exec_task WHERE batch_id = ?",
                String.class, result.batchId());
        assertFalse(aj.contains("\"leaseRef\""),
                "过期预约窗=信封不装 leaseRef（档案在位也须让位窗判定，禁误烧他机时段）: " + aj);
    }

    @Test
    void fanout_幽灵预约号_NOT_FOUND人话拒_事务回滚零写库() {
        caseId = appCase("登录页点击", "MOBILEWRIGHT", APP_ACTION_JSON);

        ServiceException ex = assertThrows(ServiceException.class, () -> matrixService.fanout(1L,
                inputWithReservation(List.of(combo("MOBILEWRIGHT", "ANDROID", "EMULATOR")), 999999999L)));
        assertEquals(BizErrorCode.NOT_FOUND, ex.getCode(), "不存在预约号=404 人话拒");
        assertTrue(ex.getMessage().contains("999999999"), "人话点名预约号: " + ex.getMessage());
        // Rollback-zero-write anchored on this run's project (shared-dev tables keep other rows).
        assertEquals(0, (int) jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM tp_exec_batch WHERE project_id = ?", Integer.class, projectId),
                "扇出@Transactional 回滚=幽灵号零半成品批");
        assertEquals(0, (int) jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM tp_exec_task WHERE project_id = ?", Integer.class, projectId),
                "扇出@Transactional 回滚=幽灵号零半成品任务");
    }

    @Test
    void seededFanoutMaxKeyPresent_in_config_table() {
        configCacheHolder.remove(KEY_FANOUT_MAX);
        String stored = jdbcTemplate.queryForObject(
                "SELECT config_value FROM tp_sys_config WHERE config_key = ?", String.class, KEY_FANOUT_MAX);
        assertEquals("12", stored, "扇出上限键必须随迁移落库在位（默认 12=冻结 S2 微裁）");
        caseId = appCase("登录页点击", "MOBILEWRIGHT", APP_ACTION_JSON);
        assertEquals(12, matrixService.precheck(input(List.of(
                combo("MOBILEWRIGHT", "ANDROID", "EMULATOR")))).fanoutMaxAllowed());
    }
}
