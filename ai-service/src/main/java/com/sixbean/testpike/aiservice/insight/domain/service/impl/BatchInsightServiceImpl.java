package com.sixbean.testpike.aiservice.insight.domain.service.impl;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.insight.api.dto.FailureRow;
import com.sixbean.testpike.aiservice.insight.domain.service.BatchInsightService;
import com.sixbean.testpike.aiservice.llm.api.dto.LlmAuditRequest;
import com.sixbean.testpike.common.mq.BatchFinalizedEventMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.sixbean.testpike.aiservice.llm.domain.component.SysConfigBooleans;

/**
 * Batch failure root-cause clustering implementation (AO-7 proactive insights,
 * stage 2b).
 * <p>Chain: switch gate (ai.insight.enabled, K8 key; closed state leaves an
 * INSIGHT_GATE_SKIP audit row naming the key and where to open it — never
 * silent) → read this batch's failed/timeout tasks from the database (status
 * 4/5, the same read basis as the batch report snapshot; read-only — results
 * land through the core write-back endpoint, two-sides separation) → cluster
 * per task: AI attribution class first ({@code ai:N}, the C3 three-class
 * scale), executor error category as the honest fallback ({@code exec:N}) with
 * the unattributed count kept visible so the card never presents category
 * grouping as finished AI attribution → land the snapshot via
 * {@link CoreGateway#upsertBatchInsight}.
 * <p>Honest absence: a batch with no failed/timeout tasks writes nothing back
 * (an empty insight card is noise). Clusters rank by count so the biggest root
 * cause leads the card; each cluster carries one failed case title as the
 * sample the reader can click through to.
 */
@Service
public class BatchInsightServiceImpl implements BatchInsightService {

    private static final Logger log = LoggerFactory.getLogger(BatchInsightServiceImpl.class);

    /** K8 switch key (read through core; missing/blank = closed, gate skip leaves a trace). */
    static final String KEY_ENABLED = "ai.insight.enabled";

    /** AI attribution three-class labels (C3 frozen scale, tp_exec_task.ai_failure_class). */
    private static final Map<Integer, String> AI_CLASS_LABELS = Map.of(
            1, "产品缺陷（AI 归因）", 2, "定位错误（AI 归因）", 3, "环境问题（AI 归因）");

    /** Executor error-category fallback labels (01 four-class scale, tp_exec_task.error_type). */
    private static final Map<Integer, String> EXEC_TYPE_LABELS = Map.of(
            1, "产品缺陷（执行器分类）", 2, "定位错误（执行器分类）",
            3, "环境问题（执行器分类）", 4, "依赖失败（执行器分类）");

    private static final String UNKNOWN_LABEL = "未分类（执行器分类缺失）";

    private final CoreGateway coreGateway;
    private final NamedParameterJdbcTemplate jdbc;

    public BatchInsightServiceImpl(CoreGateway coreGateway, NamedParameterJdbcTemplate jdbc) {
        this.coreGateway = coreGateway;
        this.jdbc = jdbc;
    }

    @Override
    public void cluster(BatchFinalizedEventMessage message) {
        Long batchId = message.batchId();
        Long projectId = message.projectId();
        if (!SysConfigBooleans.on(coreGateway.getSysConfig(KEY_ENABLED))) {
            // Closed state leaves a trace naming the key and where to open it (never silent).
            Map<String, Object> before = new LinkedHashMap<>();
            before.put("batchId", batchId);
            before.put("targetStatus", message.targetStatus());
            coreGateway.writeAudit(new LlmAuditRequest(message.operatorId(), projectId,
                    "mq_consume", "insight:batch:" + batchId, "INSIGHT_GATE_SKIP", before,
                    Map.of("action", "skip_insight_disabled",
                            "enablePath", "系统设置 → AI 配置 → " + KEY_ENABLED)));
            return;
        }
        List<FailureRow> failures = readFailedTasks(batchId);
        if (failures.isEmpty()) {
            log.info("batch insight skipped batchId={}：无失败/超时任务，诚实缺席零写回", batchId);
            return;
        }
        List<Map<String, Object>> clusters = buildClusters(failures);
        int unattributed = (int) failures.stream().filter(FailureRow::unattributed).count();
        String summary = buildSummary(failures.size(), unattributed, clusters);
        coreGateway.upsertBatchInsight(batchId, clusters, unattributed, failures.size(), summary);
        log.info("batch insight landed batchId={} failCount={} unattributed={} clusters={}",
                batchId, failures.size(), unattributed, clusters.size());
    }

    /** Failed/timeout tasks of this batch only (batch_id scoped, same 4/5 basis as BatchReportSupport). */
    private List<FailureRow> readFailedTasks(long batchId) {
        return jdbc.queryForList(
                        "SELECT t.ai_failure_class, t.error_type, c.title AS case_title"
                                + " FROM tp_exec_task t"
                                + " LEFT JOIN tp_test_case c ON c.id = t.case_id"
                                + " WHERE t.batch_id = :batchId AND t.status IN (4, 5)"
                                + " ORDER BY t.id",
                        new MapSqlParameterSource("batchId", batchId))
                .stream().map(row -> {
                    Integer aiClass = intValue(row.get("ai_failure_class"));
                    Integer errorType = intValue(row.get("error_type"));
                    return new FailureRow(aiClass,
                            aiClass == null ? null : AI_CLASS_LABELS.get(aiClass),
                            errorType,
                            errorType == null ? UNKNOWN_LABEL : EXEC_TYPE_LABELS.get(errorType),
                            row.get("case_title") == null ? null
                                    : String.valueOf(row.get("case_title")));
                })
                .toList();
    }

    /** Group by the row's attribution key (AI class wins, executor category fallback); ranked by count. */
    private static List<Map<String, Object>> buildClusters(List<FailureRow> failures) {
        Map<String, int[]> counts = new LinkedHashMap<>();
        Map<String, String> labels = new LinkedHashMap<>();
        Map<String, String> samples = new LinkedHashMap<>();
        for (FailureRow f : failures) {
            String key = f.groupKey();
            counts.computeIfAbsent(key, k -> new int[1])[0]++;
            labels.putIfAbsent(key, f.displayLabel());
            if (f.caseTitle() != null) {
                samples.putIfAbsent(key, f.caseTitle());
            }
        }
        List<Map<String, Object>> clusters = new ArrayList<>();
        counts.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<String, int[]> e) -> e.getValue()[0]).reversed()
                        .thenComparing(Map.Entry::getKey))
                .forEach(e -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("class", e.getKey());
                    item.put("label", labels.get(e.getKey()));
                    item.put("count", e.getValue()[0]);
                    item.put("sampleCaseTitle", samples.get(e.getKey()));
                    clusters.add(item);
                });
        return clusters;
    }

    /** One-line plain summary shared by the station letter and the report card. */
    private static String buildSummary(int failCount, int unattributed,
                                       List<Map<String, Object>> clusters) {
        StringBuilder sb = new StringBuilder("共 ").append(failCount).append(" 条失败，主要原因：");
        String top = clusters.stream().limit(3)
                .map(c -> c.get("label") + " " + c.get("count") + " 条")
                .reduce((a, b) -> a + "、" + b).orElse("无");
        sb.append(top);
        if (unattributed > 0) {
            sb.append("（其中 ").append(unattributed).append(" 条尚未完成智能归因，暂按执行器错误分类统计）");
        }
        return sb.toString();
    }

    private static Integer intValue(Object value) {
        return value instanceof Number n ? n.intValue() : null;
    }
}
