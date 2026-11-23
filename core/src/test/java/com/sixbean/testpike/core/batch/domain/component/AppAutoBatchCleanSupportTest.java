package com.sixbean.testpike.core.batch.domain.component;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.executor.api.dto.ExecutorRegisterInput;
import com.sixbean.testpike.core.executor.domain.component.AppAutoDispatchSupport;
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


@SpringBootTest(classes = CoreApplication.class)
class AppAutoBatchCleanSupportTest {

    private static final String ENGINE_DIMS =
            "{\"code\":\"MOBILEWRIGHT\",\"platform\":\"ANDROID\",\"deviceKind\":\"EMULATOR\"}";

    @Autowired
    private AppAutoBatchCleanSupport batchCleanSupport;

    @Autowired
    private AppAutoDispatchSupport appAutoDispatchSupport;

    @Autowired
    private ExecutorService executorService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;
    private long ownerId;
    private String serial;
    private String pkg;

    @BeforeEach
    void setUp() {
        String code = "cln" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO tp_user (username, password_hash, display_name, role, status)"
                        + " VALUES (?, 'x', '清机测试负责人', 4, 1)", "cln-u-" + code);
        ownerId = jdbcTemplate.queryForObject("SELECT id FROM tp_user WHERE username = ?",
                Long.class, "cln-u-" + code);
        jdbcTemplate.update("INSERT INTO tp_project (name, code, status, created_by) VALUES (?, ?, 1, ?)",
                "清机测试项目-" + code, code, ownerId);
        projectId = jdbcTemplate.queryForObject("SELECT id FROM tp_project WHERE code = ?",
                Long.class, code);
        serial = "cln-dev-" + System.nanoTime();
        pkg = "com.clnpkg." + System.nanoTime();
    }

    @AfterEach
    void tearDown() {
        cleanOwnRows();
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_user WHERE id = ?", ownerId);
    }

    private void cleanOwnRows() {
        jdbcTemplate.update("DELETE FROM tp_notification WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_exec_task WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_batch_case WHERE batch_id IN"
                + " (SELECT id FROM tp_exec_batch WHERE project_id = ?)", projectId);
        jdbcTemplate.update("DELETE FROM tp_exec_batch WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_script WHERE case_id IN"
                + " (SELECT id FROM tp_test_case WHERE project_id = ?)", projectId);
        jdbcTemplate.update("DELETE FROM tp_test_case WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_appauto_lease_run WHERE device_id IN"
                + " (SELECT id FROM tp_appauto_device WHERE serial LIKE 'cln-dev-%')");
        jdbcTemplate.update("DELETE FROM tp_appauto_device WHERE serial LIKE 'cln-dev-%'");
        jdbcTemplate.update("DELETE FROM tp_executor WHERE name LIKE 'cln-ex-%'");
    }


 
    private long insertBatch(int batchCleanFlag) {
        jdbcTemplate.update("INSERT INTO tp_exec_batch (project_id, batch_no, name, status, type,"
                        + " clean_app_data) VALUES (?, ?, '清机测试批', 6, 1, ?)",
                projectId, "cln-" + System.nanoTime(), batchCleanFlag);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM tp_exec_batch WHERE project_id = ? ORDER BY id DESC LIMIT 1",
                Long.class, projectId);
    }

    
    private void insertAppTask(long batchId) {
        String envelope = "{\"kind\":\"app-auto-run\",\"engine\":" + ENGINE_DIMS
                + ",\"steps\":[{\"action\":\"app.launch\",\"target\":{\"pkg\":\"" + pkg + "\"}}]}";
        jdbcTemplate.update("INSERT INTO tp_exec_task (project_id, case_id, batch_id, status,"
                        + " schema_version, action_json, task_type) VALUES (?, 0, ?, 3,"
                        + " 'appauto-v1', ?, 6)", projectId, batchId, envelope);
    }

    
    private void insertCaseWithScript(long batchId, int caseCleanFlag) {
        jdbcTemplate.update("INSERT INTO tp_test_case (case_no, project_id, title, end_type,"
                        + " status, version, clean_app_data) VALUES (?, ?, '清机用例', 'app', 1, 1, ?)",
                "cln-case-" + System.nanoTime(), projectId, caseCleanFlag);
        long caseId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_test_case WHERE project_id = ? ORDER BY id DESC LIMIT 1",
                Long.class, projectId);
        String scriptJson = "{\"version\":\"v2\",\"steps\":["
                + "{\"action\":\"app.install\",\"pkg\":\"" + pkg + "\",\"artifactKey\":\"k\"},"
                + "{\"action\":\"app.launch\",\"target\":{\"pkg\":\"" + pkg + "\"}}]}";
        jdbcTemplate.update("INSERT INTO tp_script (case_id, schema_version, action_json, status,"
                        + " generated_by, version) VALUES (?, 'v2', ?::jsonb, 1, 1, 1)",
                caseId, scriptJson);
        jdbcTemplate.update("INSERT INTO tp_batch_case (batch_id, case_id) VALUES (?, ?)",
                batchId, caseId);
    }

    private void insertDeviceLease(long batchId) {
        jdbcTemplate.update("INSERT INTO tp_appauto_device (project_id, name, serial, device_type,"
                        + " health_state, last_seen_at) VALUES (?, 'cln-dev', ?, 'ADB',"
                        + " 'GREEN', now() - interval '10 seconds')", projectId, serial);
        long deviceId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_appauto_device WHERE serial = ?", Long.class, serial);
        jdbcTemplate.update("INSERT INTO tp_appauto_lease_run (device_id, project_id, batch_id,"
                        + " holder_executor_id, state, expires_at)"
                        + " VALUES (?, ?, ?, 'cln-holder', 'RELEASED', now() + interval '1 hour')",
                deviceId, projectId, batchId);
    }

    private void quarantineDevice() {
        jdbcTemplate.update("UPDATE tp_appauto_device SET health_state = 'QUARANTINED'"
                + " WHERE serial = ?", serial);
    }

 
    private void registerCapableExecutor() {
        executorService.register(new ExecutorRegisterInput(
                "cln-ex-" + System.nanoTime(), null, null, "web,app", "chromium", 2, "1.0.0", 0,
                Map.of("engines", List.of("MOBILEWRIGHT"),
                        "platforms", List.of("ANDROID"),
                        "deviceKinds", List.of("EMULATOR"))));
    }

    private long countCleanTasks(long batchId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_exec_task WHERE project_id = ? AND task_type = 6"
                        + " AND batch_id IS NULL AND case_id = 0"
                        + " AND result_ext->>'sourceBatchId' = ?",
                Long.class, projectId, String.valueOf(batchId));
        return n == null ? 0 : n;
    }


    @Test
    void finalize_旗标开_组清机信封派发并对账形态() {
        registerCapableExecutor();
        long batchId = insertBatch(1);
        insertAppTask(batchId);
        insertCaseWithScript(batchId, 0);
        insertDeviceLease(batchId);

        batchCleanSupport.onBatchFinalized(batchId);

        assertEquals(1, countCleanTasks(batchId), "清机任务必落行（batch_id NULL 不触发再收口）");
        Map<String, Object> task = jdbcTemplate.queryForMap(
                "SELECT action_json, result_ext FROM tp_exec_task WHERE project_id = ?"
                        + " AND task_type = 6 AND batch_id IS NULL ORDER BY id DESC LIMIT 1",
                projectId);
        String actionJson = String.valueOf(task.get("action_json"));
        assertTrue(actionJson.contains("app.clearData"), "清数据步必出: " + actionJson);
        assertTrue(actionJson.contains("app.uninstall"), "卸载步必出: " + actionJson);
        assertTrue(actionJson.contains(pkg), "pkg 集必含脚本显式声明包: " + actionJson);
        assertTrue(actionJson.contains(serial), "leaseRef 必锁同设备: " + actionJson);
        assertTrue(actionJson.contains("MOBILEWRIGHT"), "引擎码必同批内任务: " + actionJson);
        String resultExt = String.valueOf(task.get("result_ext"))
                .replaceAll("\\s+", "");
        assertTrue(resultExt.contains("\"sourceBatchId\":" + batchId),
                "result_ext 溯源源批（jsonb 冒号后空格归一再比）: " + resultExt);
    }


    @Test
    void finalize_旗标全关_零动作零落库() {
        registerCapableExecutor();
        long batchId = insertBatch(0);
        insertAppTask(batchId);
        insertCaseWithScript(batchId, 0);
        insertDeviceLease(batchId);

        batchCleanSupport.onBatchFinalized(batchId);

        assertEquals(0, countCleanTasks(batchId), "旗标默认关=存量批零误清");
    }

    @Test
    void finalize_无占用流水_诚实跳过() {
        registerCapableExecutor();
        long batchId = insertBatch(1);
        insertAppTask(batchId);
        insertCaseWithScript(batchId, 0);

        batchCleanSupport.onBatchFinalized(batchId);

        assertEquals(0, countCleanTasks(batchId), "清机目标设备不可寻址=诚实跳过禁派盲单");
    }

    @Test
    void finalize_web批零app任务_零动作() {
        registerCapableExecutor();
        long batchId = insertBatch(1);
        insertCaseWithScript(batchId, 1);
        insertDeviceLease(batchId);

        batchCleanSupport.onBatchFinalized(batchId);

        assertEquals(0, countCleanTasks(batchId), "批内无 APP 自动化任务=非 appauto 批零打扰");
    }


    @Test
    void finalize_用例级旗标开_批级关也清该用例包() {
        registerCapableExecutor();
        long batchId = insertBatch(0);
        insertAppTask(batchId);
        insertCaseWithScript(batchId, 1);
        insertDeviceLease(batchId);

        batchCleanSupport.onBatchFinalized(batchId);

        assertEquals(1, countCleanTasks(batchId), "用例级旗标=独立触发位");
    }


    @Test
    void finalize_设备隔离派发被拒_吞异常留痕站内信() {
        registerCapableExecutor();
        long batchId = insertBatch(1);
        insertAppTask(batchId);
        insertCaseWithScript(batchId, 0);
        insertDeviceLease(batchId);
        quarantineDevice();

        batchCleanSupport.onBatchFinalized(batchId);

        assertEquals(0, countCleanTasks(batchId), "被拒单零落库（闸在 INSERT 前）");
        Long notes = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_notification WHERE project_id = ?"
                        + " AND event_type = 'APP_BATCH_CLEAN'", Long.class, projectId);
        assertTrue(notes != null && notes > 0, "派发失败=人话留痕站内信");
    }


    @Test
    void explain_无匹配执行器文案_不再谎称排队等待() {
        String dims = "{\"code\":\"HYPIUM\",\"platform\":\"ANDROID\",\"deviceKind\":\"EMULATOR\"}";
        String envelope = "{\"kind\":\"app-auto-run\",\"engine\":" + dims
                + ",\"steps\":[{\"action\":\"app.launch\",\"target\":{\"pkg\":\"com.demo\"}}]}";
        String reason = appAutoDispatchSupport.explainNoCapableExecutorForEnvelope(envelope);
        assertNotNull(reason, "无在线能力执行器=必有人话");
        assertFalse(reason.contains("任务将排队等待"),
                "建单侧已秒拒，「将排队等待」失真必须勘正为中性措辞: " + reason);
        assertTrue(reason.contains("HYPIUM"), "人话仍必点名引擎: " + reason);
    }
}
