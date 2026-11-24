package com.sixbean.testpike.core.appauto.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.appauto.api.dto.AppautoAiSampleCacheHitRequest;
import com.sixbean.testpike.core.appauto.api.dto.AppautoAiSampleRecordRequest;
import com.sixbean.testpike.core.support.FixtureInserts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class AppautoAiSampleTaskGateTest {

    @Autowired
    private AppautoAiSampleService aiSampleService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;
    private long caseId;
    private long taskId;
    private long deviceId;

    @BeforeEach
    void setUp() {
        String code = "apg" + System.nanoTime();
        projectId = FixtureInserts.insertReturningId(jdbcTemplate,
                "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "样本闸门测试项目-" + code, code);
        caseId = 880000L + Math.floorMod(System.nanoTime(), 1000L);
        deviceId = 870000L + Math.floorMod(System.nanoTime(), 1000L);
        taskId = FixtureInserts.insertReturningId(jdbcTemplate,
                "INSERT INTO tp_exec_task (project_id, case_id, status) VALUES (?, ?, 2)",
                projectId, caseId);
        cleanSamples();
    }

    @AfterEach
    void tearDown() {
        cleanSamples();
        jdbcTemplate.update("DELETE FROM tp_exec_task WHERE id = ?", taskId);
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
    }

    private void cleanSamples() {
        jdbcTemplate.update("DELETE FROM tp_appauto_ai_sample WHERE project_id = ?", projectId);
    }

    private AppautoAiSampleRecordRequest recordRequest(String anchor, String source,
                                                       String review, Integer x, Integer y) {
        return new AppautoAiSampleRecordRequest(taskId, deviceId, 3, anchor,
                source, review, x, y);
    }

    @Test
    void 缺任务号或步号人话拒() {
        ServiceException e = assertThrows(ServiceException.class, () ->
                aiSampleService.recordFromExecutor(
                        new AppautoAiSampleRecordRequest(null, deviceId, 1, "step1|a|fp",
                                "vision", "confirmed", 5, 5)));
        assertTrue(e.getMessage().contains("任务"), e.getMessage());
        assertThrows(ServiceException.class, () ->
                aiSampleService.cacheHitForExecutor(
                        new AppautoAiSampleCacheHitRequest(null, deviceId, 1, "step1|a|fp")));
        assertThrows(ServiceException.class, () ->
                aiSampleService.cacheHitForExecutor(
                        new AppautoAiSampleCacheHitRequest(taskId, deviceId, null, "step1|a|fp")));
    }

    @Test
    void 任务不存在人话拒零入库() {
        ServiceException e = assertThrows(ServiceException.class, () ->
                aiSampleService.recordFromExecutor(
                        new AppautoAiSampleRecordRequest(999999999L, deviceId, 1,
                                "step1|a|fp", "vision", "confirmed", 5, 5)));
        assertTrue(e.getMessage().contains("任务"), e.getMessage());
        assertThrows(ServiceException.class, () ->
                aiSampleService.cacheHitForExecutor(
                        new AppautoAiSampleCacheHitRequest(999999999L, deviceId, 1,
                                "step1|a|fp")));
        Integer left = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_appauto_ai_sample WHERE project_id = ?",
                Integer.class, projectId);
        assertEquals(0, left);
    }

    @Test
    void 回灌写样归属由任务行权威补入并可读回() {
        String anchor = "step3|app.tap|deadbeef00000000";
        long id = aiSampleService.recordFromExecutor(
                recordRequest(anchor, "vision", "confirmed", 120, 640));
        assertTrue(id > 0);
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT project_id, case_id, device_id, step_no, source_task_id "
                        + "FROM tp_appauto_ai_sample WHERE id = ?", id);
        assertEquals(projectId, ((Number) row.get("project_id")).longValue(),
                "项目号=core 按任务行反查权威补入（执行器形入参无此字段）");
        assertEquals(caseId, ((Number) row.get("case_id")).longValue(),
                "用例号=任务行真值（伪造不采信）");
        assertEquals(taskId, ((Number) row.get("source_task_id")).longValue());

        Map<String, Object> hit = aiSampleService.cacheHitForExecutor(
                new AppautoAiSampleCacheHitRequest(taskId, deviceId, 3, anchor));
        assertNotNull(hit);
        assertEquals(120, ((Number) hit.get("x")).intValue());
        assertEquals(640, ((Number) hit.get("y")).intValue());
        assertEquals("vision", hit.get("source"));
        assertNull(aiSampleService.findCacheHit(caseId + 1, deviceId, 3, anchor));
    }

    @Test
    void 值域坐标闸透传既有单点() {
        assertThrows(ServiceException.class, () ->
                aiSampleService.recordFromExecutor(
                        recordRequest("step3|a|fp", "guess", "confirmed", 1, 1)));
        assertThrows(ServiceException.class, () ->
                aiSampleService.recordFromExecutor(
                        recordRequest("step3|a|fp", "vision", "confirmed", null, null)));
    }

    @Test
    void 设备空透传通用样本面() {
        String anchor = "step2|app.input|cafe000000000000";
        aiSampleService.recordFromExecutor(
                new AppautoAiSampleRecordRequest(taskId, null, 2, anchor,
                        "manual", "confirmed", 9, 9));
        Map<String, Object> hit = aiSampleService.cacheHitForExecutor(
                new AppautoAiSampleCacheHitRequest(taskId, null, 2, anchor));
        assertNotNull(hit, "入参设备空=通用样本读写面（口径复用 findCacheHit 零第二套）");
        assertEquals(9, ((Number) hit.get("x")).intValue());
        assertTrue(aiSampleService.cacheHitForExecutor(
                new AppautoAiSampleCacheHitRequest(taskId, deviceId, 2, anchor)).isEmpty());
    }
}
