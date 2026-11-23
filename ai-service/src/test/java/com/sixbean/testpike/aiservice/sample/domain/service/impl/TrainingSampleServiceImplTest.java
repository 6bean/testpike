package com.sixbean.testpike.aiservice.sample.domain.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.sample.domain.service.TrainingSampleService;
import com.sixbean.testpike.common.mq.CaseIngestEventMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;


@SpringBootTest
class TrainingSampleServiceImplTest {

    
    private static final long CASE_BASE = 986_000_000L;

    @Autowired
    private TrainingSampleService sampleService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private CoreGateway coreGateway;

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM tp_training_sample WHERE case_id >= ? AND case_id < ?",
                CASE_BASE, CASE_BASE + 1000L);
    }

    private CaseIngestEventMessage message(long caseId, String action, int version) {
        return new CaseIngestEventMessage("REVIEW_CHANGED", caseId, 1L, 1L, action,
                null, null, null, null, 1_700_000_000_000L, version);
    }

    private Map<String, Object> reviewLog(String action, String remark, List<String> feedbackTypes) {
        return Map.of("id", 1L, "caseId", 1L, "userId", 1L, "operatorName", "评审人",
                "action", action, "remark", remark, "createdAt", "2026-09-24T10:00:00",
                "feedbackTypes", feedbackTypes);
    }

    
    @Test
    void 驳回事件_沉淀驳回样本一行含评审意见与类型() {
        long caseId = CASE_BASE + 1;
        when(coreGateway.getCaseReviewLogs(caseId)).thenReturn(List.of(
                reviewLog("拒绝", "步骤缺少断言，无法执行", List.of("步骤缺失", "预期结果不明确")),
                reviewLog("需修改", "早轮修改意见", List.of())));
        when(coreGateway.getCase(caseId)).thenReturn(Map.of(
                "id", caseId, "title", "登录成功用例", "priority", 2, "status", 1));

        sampleService.captureSample(message(caseId, "REJECT", 3));

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT sample_type, source_version, sample_json::text AS sj FROM tp_training_sample WHERE case_id = ?",
                caseId);
        assertEquals(1, rows.size(), "驳回沉淀恰 1 行（TC-D2-01）");
        Map<String, Object> row = rows.get(0);
        assertEquals("驳回", row.get("sample_type"));
        assertEquals(3, ((Number) row.get("source_version")).intValue(), "source_version=事件用例版本");
        Map<?, ?> payload = readJson(String.valueOf(row.get("sj")));
        Map<?, ?> review = (Map<?, ?>) payload.get("review");
        assertEquals("步骤缺少断言，无法执行", review.get("remark"),
                "评审意见取最新「拒绝」日志行（最新轮驳回语义，非早轮需修改） payload=" + row.get("sj"));
        assertEquals(List.of("步骤缺失", "预期结果不明确"), review.get("feedbackTypes"));
        assertEquals("登录成功用例", ((Map<?, ?>) payload.get("case")).get("title"));
    }

    
    @Test
    void 修改通过事件_沉淀成对差异样本非全文() {
        long caseId = CASE_BASE + 2;
        when(coreGateway.getCaseReviewLogs(caseId)).thenReturn(List.of(
                reviewLog("通过", "本轮合格", List.of()),
                reviewLog("需修改", "预期结果太笼统", List.of("预期结果不明确"))));
        when(coreGateway.listCaseVersions(caseId)).thenReturn(List.of(
                versionRow(5, 2), versionRow(4, 1), versionRow(3, 2)));
        when(coreGateway.getCaseVersionDiff(eq(caseId), anyInt(), anyInt())).thenReturn(Map.of(
                "caseId", caseId, "fromVersion", 3, "toVersion", 5, "truncated", false,
                "changes", List.of(Map.of("field", "expectedResult", "label", "预期结果",
                        "before", "正常", "after", "返回 200 且 body.code=0"))));

        sampleService.captureSample(message(caseId, "PASS", 5));

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT sample_type, source_version, sample_json::text AS sj FROM tp_training_sample WHERE case_id = ?",
                caseId);
        assertEquals(1, rows.size(), "修改通过沉淀恰 1 行（TC-D2-01）");
        assertEquals("修改差异", rows.get(0).get("sample_type"));
        Map<?, ?> payload = readJson(String.valueOf(rows.get(0).get("sj")));
        assertEquals(3, ((Number) payload.get("fromVersion")).intValue(),
                "基准=head 下方最近评审态变更版本（changeType=2 即 v3，非 v4 编辑） payload=" + rows.get(0).get("sj"));
        assertEquals(5, ((Number) payload.get("toVersion")).intValue());
        List<?> changes = (List<?>) payload.get("changes");
        assertEquals(1, changes.size(), "成对差异=字段级 before/after 集合");
        Map<?, ?> change = (Map<?, ?>) changes.get(0);
        assertEquals("正常", change.get("before"));
        assertEquals("返回 200 且 body.code=0", change.get("after"));
        assertFalse(payload.containsKey("case"), "差异样本非全文复制（不含 case 全文键）");
    }

    
    @Test
    void 修改通过但无需修改史_零沉淀() {
        long caseId = CASE_BASE + 3;
        when(coreGateway.getCaseReviewLogs(caseId)).thenReturn(List.of(
                reviewLog("通过", "一次通过", List.of())));

        sampleService.captureSample(message(caseId, "PASS", 2));

        assertEquals(0, sampleCount(caseId), "无修改轮的通过不产差异样本（TC-D2-01）");
    }

    
    @Test
    void 驳回样本入库脱敏_token密码掩码() {
        long caseId = CASE_BASE + 4;
        when(coreGateway.getCaseReviewLogs(caseId)).thenReturn(List.of(
                reviewLog("拒绝", "步骤里带密钥 sk-abcdef123456", List.of("步骤错误"))));
        when(coreGateway.getCase(caseId)).thenReturn(Map.of(
                "id", caseId, "title", "含敏内容用例",
                "precondition", "登录 token=abc123secret456 已过期",
                "headers", Map.of("Authorization", "Bearer sk-live-secret-xyz",
                        "inner", Map.of("password", "P@ssw0rd!"))));

        sampleService.captureSample(message(caseId, "REJECT", 7));

        String stored = jdbcTemplate.queryForObject(
                "SELECT sample_json::text FROM tp_training_sample WHERE case_id = ?", String.class, caseId);
        assertNotNull(stored);
        assertTrue(stored.contains("***MASKED***"), "敏感键值落库=掩码（TC-D2-02）");
        assertFalse(stored.contains("sk-live-secret-xyz"), "Bearer 凭据值零留存");
        assertFalse(stored.contains("P@ssw0rd!"), "嵌套层 password 全深度掩码（IT-D2-01 口径）");
        assertTrue(stored.contains("Bearer ***MASKED***"), "认证方案前缀保留可读");
    }

    
    @Test
    void 同用例重复驳回_多行带轮次非覆盖() {
        long caseId = CASE_BASE + 5;
        when(coreGateway.getCaseReviewLogs(caseId)).thenReturn(List.of(
                reviewLog("拒绝", "第一轮驳回", List.of("步骤缺失"))));
        when(coreGateway.getCase(caseId)).thenReturn(Map.of("id", caseId, "title", "反复驳回用例"));

        sampleService.captureSample(message(caseId, "REJECT", 3));
        when(coreGateway.getCaseReviewLogs(caseId)).thenReturn(List.of(
                reviewLog("拒绝", "第二轮驳回", List.of("优先级错误"))));
        sampleService.captureSample(message(caseId, "REJECT", 5));

        assertEquals(2, sampleCount(caseId), "重复驳回=多行样本带轮次，非 upsert 覆盖（IT-D2-01）");
        List<Integer> versions = jdbcTemplate.queryForList(
                "SELECT source_version FROM tp_training_sample WHERE case_id = ? ORDER BY source_version",
                Integer.class, caseId);
        assertEquals(List.of(3, 5), versions);
    }

    private Map<?, ?> readJson(String json) {
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            throw new IllegalStateException("样本 JSON 不可解析", e);
        }
    }

    private int sampleCount(long caseId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_training_sample WHERE case_id = ?", Integer.class, caseId);
        return count == null ? 0 : count;
    }

    private static Map<String, Object> versionRow(int version, int changeType) {
        return Map.of("id", (long) version, "caseId", 1L, "version", version,
                "changeType", changeType, "operatorId", 1L, "operatorName", "张三",
                "createdAt", "2026-09-24T10:00:00", "changedFieldCount", 1, "corrupted", false);
    }
}
