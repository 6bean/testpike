package com.sixbean.testpike.aiservice.insight.domain.service.impl;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.insight.domain.service.BatchInsightService;
import com.sixbean.testpike.aiservice.llm.api.dto.LlmAuditRequest;
import com.sixbean.testpike.common.mq.BatchFinalizedEventMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Contract test for the batch failure-insight clustering service (AO-7 stage 2b).
 * <p>Design pins:
 * ① clustering source = the batch's failed/timeout tasks (status 4/5, same read basis
 * as the report snapshot in BatchReportSupport) — AI attribution class first,
 * executor error category as the honest fallback with the unattributed count kept
 * visible;
 * ② results land through the core write-back endpoint only (two-sides separation —
 * ai-service reads business tables but never writes them);
 * ③ ai.insight.enabled off state = zero write-back plus a gate-skip audit row naming
 * the switch key and where to turn it on (R32 closed-state guidance, never silent);
 * ④ a batch with no failed tasks produces nothing (an empty insight card is noise —
 * honest absence: zero write-back, zero audit).
 * <p>Read side runs against the real dev database (nothing mocked there); CoreGateway
 * is mocked only at its HTTP boundary (L2 infrastructure isolation, classify precedent).
 */
@SpringBootTest
class BatchInsightClusteringTest {

    /** Test-only project id segment (fixture rows anchor here, @AfterEach clears the segment). */
    private static final long PROJECT_BASE = 987_000_000L;

    @Autowired
    private BatchInsightService batchInsightService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private CoreGateway coreGateway;

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM tp_exec_task WHERE project_id >= ? AND project_id < ?",
                PROJECT_BASE, PROJECT_BASE + 1000L);
        jdbcTemplate.update("DELETE FROM tp_exec_batch WHERE project_id >= ? AND project_id < ?",
                PROJECT_BASE, PROJECT_BASE + 1000L);
        jdbcTemplate.update("DELETE FROM tp_test_case WHERE project_id >= ? AND project_id < ?",
                PROJECT_BASE, PROJECT_BASE + 1000L);
        jdbcTemplate.update("DELETE FROM tp_project WHERE id >= ? AND id < ?",
                PROJECT_BASE, PROJECT_BASE + 1000L);
    }

    private long newProject(long projectId) {
        // tp_project.id is GENERATED ALWAYS — explicit fixture ids need the
        // OVERRIDING SYSTEM VALUE clause (keeps the fixed segment for cleanup).
        jdbcTemplate.update("INSERT INTO tp_project (id, name, code) OVERRIDING SYSTEM VALUE VALUES (?, ?, ?)",
                projectId, "洞察聚类项目", "INS" + projectId);
        return projectId;
    }

    private long newBatch(long projectId) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO tp_exec_batch (project_id, batch_no, name, status, type) "
                        + "VALUES (?, ?, '聚类批次', 7, 1) RETURNING id",
                Long.class, projectId, "B-" + System.nanoTime());
    }

    private long newCase(long projectId, String title) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO tp_test_case (case_no, project_id, title) VALUES (?, ?, ?) RETURNING id",
                Long.class, "TC-INS-" + System.nanoTime(), projectId, title);
    }

    /** status 4 = failed, 5 = timeout feed the clustering; a non-null aiClass wins over the executor error_type fallback. */
    private void insertTask(long batchId, long projectId, long caseId, int status,
                            Integer aiClass, Integer errorType, String errorDetail) {
        jdbcTemplate.update("INSERT INTO tp_exec_task (project_id, case_id, batch_id, status, "
                        + "error_type, error_detail, ai_failure_class) VALUES (?, ?, ?, ?, ?, ?, ?)",
                projectId, caseId, batchId, status, errorType, errorDetail, aiClass);
    }

    private BatchFinalizedEventMessage message(long projectId, long batchId, Integer targetStatus) {
        return new BatchFinalizedEventMessage("EXEC_BATCH_FINALIZED", null, projectId, null,
                null, null, null, null, null, 1_700_000_000_000L, null,
                null, null, null, null, batchId, targetStatus);
    }

    private static Map<String, Object> clusterBy(List<Map<String, Object>> clusters, String key) {
        return clusters.stream().filter(c -> key.equals(c.get("class"))).findFirst().orElse(null);
    }

    /** (3) Switch off = zero write-back, but a gate-skip audit row must name the key and where to enable it (never silent). */
    @Test
    void switchOff_zeroWritebackButGateSkipAuditNamesKeyAndPath() {
        long projectId = newProject(PROJECT_BASE + 1);
        long batchId = newBatch(projectId);
        when(coreGateway.getSysConfig("ai.insight.enabled")).thenReturn("0");

        batchInsightService.cluster(message(projectId, batchId, 7));

        verify(coreGateway, never()).upsertBatchInsight(anyLong(), any(), anyInt(), anyInt(), any());
        ArgumentCaptor<LlmAuditRequest> captor = ArgumentCaptor.forClass(LlmAuditRequest.class);
        verify(coreGateway).writeAudit(captor.capture());
        LlmAuditRequest audit = captor.getValue();
        assertEquals("INSIGHT_GATE_SKIP", audit.action());
        assertEquals(projectId, audit.projectId());
        String rendered = String.valueOf(audit.before()) + String.valueOf(audit.after());
        assertTrue(rendered.contains("ai.insight.enabled"),
                "关态留痕必须点名参数键: " + rendered);
        assertTrue(rendered.contains("系统设置") && rendered.contains("AI"),
                "关态留痕必须指路打开位置: " + rendered);
    }

    /** (1) Mixed attribution: AI class wins, unattributed rows fall back to the executor category with the count kept visible; passed tasks stay out. */
    @Test
    void failedTasks_aiAttributionFirst_categoryFallback_countsVisible() {
        long projectId = newProject(PROJECT_BASE + 2);
        long batchId = newBatch(projectId);
        long caseA = newCase(projectId, "登录页提交按钮用例");
        long caseB = newCase(projectId, "订单列表分页用例");
        long caseC = newCase(projectId, "支付回调超时用例");
        insertTask(batchId, projectId, caseA, 4, 1, 1, "断言不符：预期 200 实得 500");
        insertTask(batchId, projectId, caseB, 4, null, 2, "元素未找到 #submit");
        insertTask(batchId, projectId, caseC, 5, null, 3, "连接超时");
        insertTask(batchId, projectId, caseA + 1000, 3, null, null, null);   // a passed task stays out of the clustering
        when(coreGateway.getSysConfig("ai.insight.enabled")).thenReturn("1");

        batchInsightService.cluster(message(projectId, batchId, 7));

        ArgumentCaptor<List<Map<String, Object>>> clustersCap = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Integer> unattrCap = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> failCap = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<String> summaryCap = ArgumentCaptor.forClass(String.class);
        verify(coreGateway).upsertBatchInsight(eq(batchId), clustersCap.capture(),
                unattrCap.capture(), failCap.capture(), summaryCap.capture());
        assertEquals(3, failCap.getValue(), "失败+超时共 3 条入聚类");
        assertEquals(2, unattrCap.getValue(), "2 条无 AI 归因（执行器分类回退）");
        List<Map<String, Object>> clusters = clustersCap.getValue();
        assertEquals(3, clusters.size(), "ai:1 / exec:2 / exec:3 三组");
        Map<String, Object> aiGroup = clusterBy(clusters, "ai:1");
        assertNotNull(aiGroup, "AI 归因组存在");
        assertEquals(1, aiGroup.get("count"));
        assertTrue(String.valueOf(aiGroup.get("label")).contains("产品缺陷"),
                "AI 归因组 label=三分类口径: " + aiGroup.get("label"));
        Map<String, Object> locatorGroup = clusterBy(clusters, "exec:2");
        assertNotNull(locatorGroup);
        assertTrue(String.valueOf(locatorGroup.get("sampleCaseTitle")).contains("订单列表"),
                "代表样本=组内失败用例标题: " + locatorGroup);
        assertNotNull(clusterBy(clusters, "exec:3"));
        assertNotNull(summaryCap.getValue());
        assertTrue(summaryCap.getValue().contains("3"), "摘要点名失败总数: " + summaryCap.getValue());
    }

    /** (4) A batch with no failures = honest absence: zero write-back, zero audit (an empty insight card is noise). */
    @Test
    void batchWithoutFailedTasks_honestAbsence_zeroWritebackZeroAudit() {
        long projectId = newProject(PROJECT_BASE + 3);
        long batchId = newBatch(projectId);
        long caseId = newCase(projectId, "全通过用例");
        insertTask(batchId, projectId, caseId, 3, null, null, null);
        when(coreGateway.getSysConfig("ai.insight.enabled")).thenReturn("1");

        batchInsightService.cluster(message(projectId, batchId, 6));

        verify(coreGateway, never()).upsertBatchInsight(anyLong(), any(), anyInt(), anyInt(), any());
        verify(coreGateway, never()).writeAudit(any());
    }

    /** (2) Scoping: the clustering reads only this batch's task rows (batch_id pinned; cross-batch failures stay out). */
    @Test
    void clusteringScopedToFinalizedBatch_otherBatchFailuresIgnored() {
        long projectId = newProject(PROJECT_BASE + 4);
        long batchId = newBatch(projectId);
        long otherBatchId = newBatch(projectId);
        long caseId = newCase(projectId, "本批失败用例");
        long otherCase = newCase(projectId, "他批失败用例");
        insertTask(batchId, projectId, caseId, 4, null, 1, "断言不符");
        insertTask(otherBatchId, projectId, otherCase, 4, null, 2, "元素未找到");
        when(coreGateway.getSysConfig("ai.insight.enabled")).thenReturn("1");

        batchInsightService.cluster(message(projectId, batchId, 7));

        ArgumentCaptor<List<Map<String, Object>>> clustersCap = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Integer> failCap = ArgumentCaptor.forClass(Integer.class);
        verify(coreGateway).upsertBatchInsight(eq(batchId), clustersCap.capture(),
                anyInt(), failCap.capture(), any());
        assertEquals(1, failCap.getValue(), "只聚本批失败");
        assertEquals(1, clustersCap.getValue().size());
        assertEquals("exec:1", clustersCap.getValue().get(0).get("class"));
    }
}
