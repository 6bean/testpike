package com.sixbean.testpike.core.appauto.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.appauto.api.dto.AppautoAiSampleInput;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class AppautoAiSampleServiceTest {

    @Autowired
    private AppautoAiSampleService aiSampleService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;

    @BeforeEach
    void setUp() {
        String code = "aps" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "样本库测试项目-" + code, code);
        projectId = jdbcTemplate.queryForObject("SELECT id FROM tp_project WHERE code = ?",
                Long.class, code);
        cleanSamples();
    }

    @AfterEach
    void tearDown() {
        cleanSamples();
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
    }

    private void cleanSamples() {
        jdbcTemplate.update("DELETE FROM tp_appauto_ai_sample WHERE project_id = ?", projectId);
    }

    private AppautoAiSampleInput sample(long caseId, long deviceId, int stepNo,
                                        String anchor, String source, String result,
                                        Integer x, Integer y) {
        return new AppautoAiSampleInput(projectId, caseId, deviceId, stepNo, anchor,
                source, result, x, y, null);
    }

    @Test
    void 写样后列举原样往返() {
        long id = aiSampleService.recordSample(
                sample(7001L, 8001L, 3, "tap:登录按钮", "vision", "confirmed", 120, 640));
        assertTrue(id > 0);
        aiSampleService.recordSample(
                sample(7001L, 8001L, 4, "tap:提交", "a11y", "rejected", null, null));
        List<Map<String, Object>> rows = aiSampleService.listSamples(projectId, 7001L, 50);
        assertEquals(2, rows.size());
        Map<String, Object> first = rows.get(0);
        assertEquals("tap:提交", first.get("anchor"));
        assertEquals("rejected", first.get("reviewResult"));
        Map<String, Object> second = rows.get(1);
        assertEquals("vision", second.get("hitSource"));
        assertEquals(120, ((Number) second.get("x")).intValue());
        assertEquals(640, ((Number) second.get("y")).intValue());
    }

    @Test
    void 复核确认缺坐标人话拒() {
        ServiceException e = assertThrows(ServiceException.class, () ->
                aiSampleService.recordSample(
                        sample(7002L, 8001L, 1, "tap:锚", "vision", "confirmed", null, null)));
        assertTrue(e.getMessage().contains("坐标"), e.getMessage());
    }

    @Test
    void 值域外枚举人话拒() {
        assertThrows(ServiceException.class, () ->
                aiSampleService.recordSample(
                        sample(7003L, 8001L, 1, "tap:锚", "guess", "confirmed", 1, 1)));
        assertThrows(ServiceException.class, () ->
                aiSampleService.recordSample(
                        sample(7003L, 8001L, 1, "tap:锚", "vision", "pending", 1, 1)));
    }

    @Test
    void 锚空白与步号负数人话拒() {
        assertThrows(ServiceException.class, () ->
                aiSampleService.recordSample(
                        sample(7004L, 8001L, 1, "  ", "manual", "confirmed", 1, 1)));
        assertThrows(ServiceException.class, () ->
                aiSampleService.recordSample(
                        sample(7004L, 8001L, -1, "tap:锚", "manual", "confirmed", 1, 1)));
    }

    @Test
    void 同屏读样命中最新确认坐标() {
        aiSampleService.recordSample(
                sample(7005L, 8001L, 2, "tap:搜索框", "vision", "confirmed", 10, 20));
        aiSampleService.recordSample(
                sample(7005L, 8001L, 2, "tap:搜索框", "cache", "confirmed", 30, 40));
        aiSampleService.recordSample(
                sample(7005L, 8001L, 2, "tap:搜索框", "vision", "rejected", 90, 90));
        Map<String, Object> hit = aiSampleService.findCacheHit(7005L, 8001L, 2, "tap:搜索框");
        assertNotNull(hit);
        assertEquals(30, ((Number) hit.get("x")).intValue());
        assertEquals(40, ((Number) hit.get("y")).intValue());
        assertEquals("cache", hit.get("source"));
        assertNull(aiSampleService.findCacheHit(7005L, 8001L, 2, "tap:别的按钮"));
        assertNull(aiSampleService.findCacheHit(7005L, 8002L, 2, "tap:搜索框"));
        aiSampleService.recordSample(
                sample(7006L, 8001L, 1, "tap:纯驳回", "vision", "rejected", 5, 5));
        assertNull(aiSampleService.findCacheHit(7006L, 8001L, 1, "tap:纯驳回"));
    }

    @Test
    void 命中率统计程序化实算() {
        aiSampleService.recordSample(
                sample(7007L, 8001L, 1, "a", "vision", "confirmed", 1, 1));
        aiSampleService.recordSample(
                sample(7007L, 8001L, 2, "b", "a11y", "confirmed", 2, 2));
        aiSampleService.recordSample(
                sample(7007L, 8001L, 3, "c", "vision", "rejected", null, null));
        Map<String, Object> stats = aiSampleService.hitRateStats(projectId, 7007L);
        assertEquals(3L, ((Number) stats.get("total")).longValue());
        assertEquals(2L, ((Number) stats.get("confirmed")).longValue());
        assertEquals(1L, ((Number) stats.get("rejected")).longValue());
        assertEquals(0.6667, ((Number) stats.get("confirmRate")).doubleValue(), 0.0001);
        Map<String, Object> empty = aiSampleService.hitRateStats(projectId, 7999L);
        assertEquals(0L, ((Number) empty.get("total")).longValue());
        assertEquals(0.0, ((Number) empty.get("confirmRate")).doubleValue(), 0.0001);
    }
}
