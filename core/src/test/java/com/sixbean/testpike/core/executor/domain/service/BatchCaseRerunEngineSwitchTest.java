package com.sixbean.testpike.core.executor.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.executor.api.dto.BatchCaseRerunResult;
import com.sixbean.testpike.core.executor.api.dto.ExecutorRegisterInput;
import com.sixbean.testpike.core.executor.domain.component.AppAutoDispatchSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
@org.springframework.test.context.TestPropertySource(properties = "testpike.scheduling.enabled=false")
class BatchCaseRerunEngineSwitchTest {

    
    private static final String APP_STEPS = """
            {"steps":[
              {"stepNo":1,"action":"app.launch","target":{"pkg":"com.demo.app"}},
              {"stepNo":2,"action":"app.tap","target":{"accessibilityId":"login_btn"}},
              {"stepNo":3,"action":"app.assertText","text":"欢迎"},
              {"stepNo":4,"action":"app.close"}]}
            """;

    
    private static final String WEB_ACTION_JSON =
            "{\"version\":\"v2\",\"steps\":[{\"action\":\"goto\",\"target\":{\"url\":\"http://demo.local\"}}]}";

    @Autowired
    private ExecTaskService execTaskService;
    @Autowired
    private com.sixbean.testpike.core.executor.domain.service.ExecutorService executorService;
    @Autowired
    private AppAutoDispatchSupport appAutoDispatchSupport;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;
    private long batchId;
    private long caseId;
    private long appTaskId;

    @BeforeEach
    void seed() {
        cleanup();
        String code = "engsw" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "换引擎重跑项目-" + code, code);
        projectId = jdbcTemplate.queryForObject("SELECT id FROM tp_project WHERE code = ?",
                Long.class, code);
        Long envId = jdbcTemplate.queryForObject("""
                INSERT INTO tp_project_env (project_id, name, env_type, base_url)
                VALUES (?, '换引擎测试环境', 2, 'http://demo.local') RETURNING id
                """, Long.class, projectId);
        batchId = jdbcTemplate.queryForObject("""
                INSERT INTO tp_exec_batch (project_id, env_id, batch_no, name, status, type, schedule_type)
                VALUES (?, ?, 'B-ENGSW', '换引擎重跑批次', 4, 1, 0) RETURNING id
                """, Long.class, projectId, envId);
        caseId = jdbcTemplate.queryForObject("""
                INSERT INTO tp_test_case (case_no, project_id, title, status, source, created_by,
                                          automation_target, appauto_engine)
                VALUES (?, ?, '换引擎重跑用例', 7, 1, 1, 'app', 'MOBILEWRIGHT') RETURNING id
                """, Long.class, "TC-ENGSW-" + System.nanoTime() % 1000000, projectId);
        jdbcTemplate.update("INSERT INTO tp_script (case_id, schema_version, action_json) "
                + "VALUES (?, 'v2', ?::jsonb)", caseId, APP_STEPS);
        executorService.register(new com.sixbean.testpike.core.executor.api.dto.ExecutorRegisterInput(
                "engsw-ex-" + System.nanoTime(), null, null, "web,app", "chromium", 2, "1.0.0", 0,
                Map.of("engines", List.of("MOBILEWRIGHT", "APPIUM", "MAESTRO"),
                        "platforms", List.of("ANDROID", "IOS"),
                        "deviceKinds", List.of("EMULATOR", "REAL", "SIMULATOR"))));
        Map<String, Object> envelope = appAutoDispatchSupport.buildEnvelope(
                projectId, "MOBILEWRIGHT", null, stepsOf(APP_STEPS));
        appTaskId = execTaskService.createAppAutoRun(projectId, caseId, batchId,
                toJson(envelope));
        markTerminal(appTaskId);
    }

    @AfterEach
    void restore() {
        jdbcTemplate.update("UPDATE tp_appauto_engine SET capabilities = "
                + "capabilities || '{\"tap\": true}'::jsonb WHERE code = 'APPIUM'");
        cleanup();
    }

    private void cleanup() {
        jdbcTemplate.update("DELETE FROM tp_executor WHERE name LIKE 'engsw-ex-%'");
        jdbcTemplate.update("DELETE FROM tp_exec_task WHERE batch_id IN "
                + "(SELECT id FROM tp_exec_batch WHERE batch_no = 'B-ENGSW')");
        jdbcTemplate.update("DELETE FROM tp_exec_batch WHERE batch_no = 'B-ENGSW'");
        jdbcTemplate.update("DELETE FROM tp_script WHERE case_id IN "
                + "(SELECT id FROM tp_test_case WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'engsw%'))");
        jdbcTemplate.update("DELETE FROM tp_test_case WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'engsw%')");
        jdbcTemplate.update("DELETE FROM tp_project_env WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'engsw%')");
        jdbcTemplate.update("DELETE FROM tp_project WHERE code LIKE 'engsw%'");
    }

    
    private void markTerminal(long taskId) {
        jdbcTemplate.update("UPDATE tp_exec_task SET status = 4, finished_at = now() WHERE id = ?", taskId);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> stepsOf(String actionJson) {
        return (List<Map<String, Object>>) appAutoDispatchSupport.extractAppSteps(actionJson);
    }

    private String toJson(Object value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }


    @Test
    void rerun_换引擎DEGRADED_新任务信封指向目标引擎且保持appauto分流() {
        BatchCaseRerunResult result = execTaskService.rerunBatchCase(appTaskId, null, "maestro");

        assertTrue(result.created(), "换引擎重跑必须新建任务");
        assertNotEquals(appTaskId, result.taskId());
        assertTrue(result.message().contains("Maestro"), "人话含目标引擎名：" + result.message());
        Map<String, Object> task = jdbcTemplate.queryForMap(
                "SELECT batch_id, case_id, status, task_type, schema_version, action_json::text AS aj "
                        + "FROM tp_exec_task WHERE id = ?", result.taskId());
        assertEquals(6, ((Number) task.get("task_type")).intValue(), "换引擎重跑保持 APP 分流（禁走 web create 通道）");
        assertEquals("appauto-v1", task.get("schema_version"));
        assertEquals(batchId, ((Number) task.get("batch_id")).longValue(), "重跑任务挂同一批次");
        assertEquals(caseId, ((Number) task.get("case_id")).longValue());
        assertEquals(1, ((Number) task.get("status")).intValue(), "新任务待派发");
        String aj = String.valueOf(task.get("aj"));
        assertTrue(aj.contains("MAESTRO"), "信封 engine.code 必须重建为目标引擎：" + aj.substring(0, Math.min(aj.length(), 200)));
        assertTrue(aj.contains("app.launch"), "复用源信封 steps 快照（重跑不改编排）");
        Integer caseStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM tp_test_case WHERE id = ?", Integer.class, caseId);
        assertEquals(6, caseStatus, "重跑开始：执行失败(7)→执行中(6)");
    }

    @Test
    void rerun_换引擎RUNNABLE_同项目描述符全命中放行() {
        BatchCaseRerunResult result = execTaskService.rerunBatchCase(appTaskId, null, "APPIUM");
        assertTrue(result.created());
        String aj = jdbcTemplate.queryForObject(
                "SELECT action_json::text FROM tp_exec_task WHERE id = ?", String.class, result.taskId());
        assertTrue(aj.contains("APPIUM"), "信封重建指向 APPIUM");
    }


    @Test
    void rerun_换引擎REWRITE_拒派点名步号动词回改写清单() {
        jdbcTemplate.update("UPDATE tp_appauto_engine SET capabilities = "
                + "capabilities || '{\"tap\": false}'::jsonb WHERE code = 'APPIUM'");

        ServiceException ex = assertThrows(ServiceException.class,
                () -> execTaskService.rerunBatchCase(appTaskId, null, "APPIUM"));

        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("app.tap"), "改写清单点名动词：" + ex.getMessage());
        Integer cnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tp_exec_task WHERE batch_id = ? AND case_id = ?",
                Integer.class, batchId, caseId);
        assertEquals(1, cnt, "拒派不得新建任务（不烧执行器会话）");
    }

    @Test
    void rerun_换停用引擎_预检面人话拒不给可跑假象() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> execTaskService.rerunBatchCase(appTaskId, null, "HYPIUM"));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("停用") || ex.getMessage().contains("下架"),
                "人话含停用/下架：" + ex.getMessage());
    }


    @Test
    void rerun_非APP任务带引擎覆盖_人话拒() {
        Long envId = jdbcTemplate.queryForObject(
                "SELECT env_id FROM tp_exec_batch WHERE id = ?", Long.class, batchId);
        long webTask = execTaskService.create(new com.sixbean.testpike.core.executor.api.dto
                .ExecTaskCreateInput(projectId, caseId, "v2", WEB_ACTION_JSON, envId, batchId));
        markTerminal(webTask);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> execTaskService.rerunBatchCase(webTask, null, "MAESTRO"));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("App") || ex.getMessage().contains("APP"),
                "人话点名仅适用 APP 任务：" + ex.getMessage());
    }


    @Test
    void rerun_不带引擎覆盖_沿用源信封零漂移() {
        BatchCaseRerunResult result = execTaskService.rerunBatchCase(appTaskId, null, null);
        assertTrue(result.created());
        Map<String, Object> task = jdbcTemplate.queryForMap(
                "SELECT task_type, action_json::text AS aj FROM tp_exec_task WHERE id = ?",
                result.taskId());
        assertTrue(String.valueOf(task.get("aj")).contains("MOBILEWRIGHT"),
                "缺省=原引擎沿用（快照复用，行为与既有一致）");
        assertEquals(6, ((Number) task.get("task_type")).intValue(),
                "APP 任务重跑必须保持 APP 分流（禁漂移 web 通道致派发错型）");
    }


    @Test
    void rerun_幂等命中优先于换引擎_返回既有任务() {
        BatchCaseRerunResult first = execTaskService.rerunBatchCase(appTaskId, null, null);
        assertTrue(first.created());

        BatchCaseRerunResult again = execTaskService.rerunBatchCase(appTaskId, null, "APPIUM");
        assertEquals(first.taskId(), again.taskId(), "IT-O9-01①：排队中的重跑任务优先返回，不双建");
        assertEquals(false, again.created());
        assertNotNull(again.message());
    }
}
