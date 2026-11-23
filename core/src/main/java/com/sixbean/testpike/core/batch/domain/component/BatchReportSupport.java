package com.sixbean.testpike.core.batch.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sixbean.testpike.common.report.PassRateSql;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Batch-level summary report snapshot writer.
 * <p>Semantics: the report is a <b>finalization-time snapshot entity</b>: the moment when the batch actually advances to Completed(6) or Partially Failed(7).
 * (BatchFinalizeSupport advanced&gt;0 guard bit, in the same transaction as finalization) persist a single tp_batch_report row once.
 * Subsequent notifications and report read paths read this same row, <b>recalculation at query time is forbidden</b> (single-source discipline).
 * <p>Idempotency root = uk(batch_id): {@code INSERT ... ON CONFLICT (batch_id) DO UPDATE}.
 * Second finalization after abnormal operations roll back the batch status overwrites the row instead of stacking rows.
 * <p>Single-source metric definition: pass_rate references {@link PassRateSql#RATE_EXPR} (no second calculation).
 * Counts are calculated directly from terminal task status codes: 3 success, 4 failure, 5 timeout, 6 skipped.
 * fail_dist_json is the four-category error_type distribution of failed or timeout tasks, with NULL mapped to the "0" bucket.
 * attr_json is the three-category proportion of AI attribution for failed or timeout tasks (V94 column, with NULL treated as unclassified and mapped to the "unclassified" bucket).
 * duration_ms is finished_at minus started_at, with NULL if either endpoint is missing.
 * <p>mock_task_cnt (V238, ⑤c): counts tasks whose result_ext carries a
 * non-empty mockNotes audit bucket. The empty-array form written by non-mock tasks
 * never matches, so ordinary batches snapshot 0 with zero drift. Captured at finalize
 * exactly like every other column — the read path forwards it verbatim.
 * <p>llm_task_cnt: counts tasks whose mockNotes array carries at least one audit
 * row stamped source='llm' — the hard provenance the traffic plane writes when the
 * paid LLM fallback rung answered a miss. An llm-served task counts into both
 * dimensions (an llm answer is still a mock served instead of the real
 * integration); stub-only or unlabeled rows never match, so the two counts stay
 * honest about how much of a "passing" batch was actually AI-generated.
 * <p>mobile_perf_json + prev_batch_id (V486): finalize-time rollup of the mobile sampling
 * numbers the executor reported per task under {@code result_ext.mobilePerfSummary}
 * (shape {@code {"metrics":{name:{"median":x,"count":n}},"sampleCount":k}}). The batch
 * value of one metric is the median across that metric's task-level medians rounded to
 * two decimals, and taskCount is the number of tasks that reported it. The previous
 * cycle is the nearest earlier finalized batch sharing the same project and environment
 * (NULL environment matched with IS NOT DISTINCT FROM); prevMedian/delta are emitted
 * only when the previous batch reported the same metric — a metric it never reported
 * carries no comparison fields rather than a zero-filled one. A batch without any
 * reported metric keeps the column NULL (honest absence, never an empty object).
 * This component is the single median-rollup source: per-task readings stay on the
 * executor side, the report read path forwards the snapshot verbatim, and no query-time
 * recomputation or second calculation site is introduced.
 */
@Component
public class BatchReportSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbcTemplate;

    public BatchReportSupport(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Snapshot SQL uses {@code __RATE_EXPR__} as the placeholder for the PassRateSql.RATE_EXPR single-source injection slot.
     *  The text block ending must not concatenate on the same line, using the placeholder replacement pattern. */
    private static final String SNAPSHOT_SQL = """
                INSERT INTO tp_batch_report
                    (batch_id, project_id, total, pass_cnt, fail_cnt, skip_cnt, timeout_cnt,
                     pass_rate, fail_dist_json, attr_json, duration_ms, env_version_snapshot,
                     mock_task_cnt, llm_task_cnt, mobile_perf_json, prev_batch_id, batch_status, created_at, updated_at)
                SELECT b.id, b.project_id,
                       count(t.id),
                       count(*) FILTER (WHERE t.status = 3),
                       count(*) FILTER (WHERE t.status = 4),
                       count(*) FILTER (WHERE t.status = 6),
                       count(*) FILTER (WHERE t.status = 5),
                       __RATE_EXPR__,
                       (SELECT COALESCE(jsonb_object_agg(k, c), '{}'::jsonb) FROM (
                           SELECT COALESCE(f.error_type::text, '0') AS k, count(*) AS c
                             FROM tp_exec_task f
                            WHERE f.batch_id = b.id AND f.status IN (4, 5)
                            GROUP BY 1) s),
                       (SELECT COALESCE(jsonb_object_agg(k, c), '{}'::jsonb) FROM (
                           SELECT COALESCE(a.ai_failure_class::text, 'unclassified') AS k, count(*) AS c
                             FROM tp_exec_task a
                            WHERE a.batch_id = b.id AND a.status IN (4, 5)
                            GROUP BY 1) s),
                       CASE WHEN b.finished_at IS NOT NULL AND b.started_at IS NOT NULL
                            THEN (EXTRACT(EPOCH FROM (b.finished_at - b.started_at)) * 1000)::bigint END,
                       b.env_version_snapshot,
                       count(*) FILTER (WHERE (t.result_ext->>'mockNotes') IS NOT NULL
                                              AND t.result_ext->>'mockNotes' <> '[]'),
                       -- source=llm provenance via jsonb containment (equivalent to an
                       -- EXISTS over jsonb_array_elements, which aggregate FILTER cannot
                       -- host: a set-returning function is not allowed inside a FILTER).
                       count(*) FILTER (WHERE t.result_ext->'mockNotes'
                                          @> '[{"source":"llm"}]'::jsonb),
                       ?::jsonb, ?::bigint, ?, now(), now()
                  FROM tp_exec_batch b
                  LEFT JOIN tp_exec_task t ON t.batch_id = b.id
                 WHERE b.id = ?
                 GROUP BY b.id, b.project_id, b.finished_at, b.started_at, b.env_version_snapshot
                ON CONFLICT (batch_id) DO UPDATE SET
                    total = EXCLUDED.total, pass_cnt = EXCLUDED.pass_cnt,
                    fail_cnt = EXCLUDED.fail_cnt, skip_cnt = EXCLUDED.skip_cnt,
                    timeout_cnt = EXCLUDED.timeout_cnt, pass_rate = EXCLUDED.pass_rate,
                    fail_dist_json = EXCLUDED.fail_dist_json, attr_json = EXCLUDED.attr_json,
                    duration_ms = EXCLUDED.duration_ms,
                    env_version_snapshot = EXCLUDED.env_version_snapshot,
                    mock_task_cnt = EXCLUDED.mock_task_cnt,
                    llm_task_cnt = EXCLUDED.llm_task_cnt,
                    mobile_perf_json = EXCLUDED.mobile_perf_json,
                    prev_batch_id = EXCLUDED.prev_batch_id,
                    batch_status = EXCLUDED.batch_status, updated_at = now()
                """;

    /** Task-level mobile sampling rows the executor trailing key landed in result_ext. */
    private static final String MOBILE_TASK_SQL =
            "SELECT result_ext->'mobilePerfSummary'->'metrics'::text AS metrics_json,"
          + " result_ext->'mobilePerfSummary'->>'sampleCount' AS sample_count"
          + " FROM tp_exec_task"
          + " WHERE batch_id = ? AND result_ext->'mobilePerfSummary' IS NOT NULL";

    /** Previous cycle = nearest earlier finalized batch (a tp_batch_report row exists) of
     *  the same project and environment; the current batch itself is excluded by identity. */
    private static final String PREV_BATCH_SQL =
            "SELECT r.batch_id, r.mobile_perf_json::text AS mobile_perf_json"
          + " FROM tp_batch_report r"
          + " JOIN tp_exec_batch pb ON pb.id = r.batch_id"
          + " JOIN tp_exec_batch cb ON cb.id = ?"
          + " WHERE pb.project_id = cb.project_id"
          + "   AND pb.env_id IS NOT DISTINCT FROM cb.env_id"
          + "   AND pb.id <> cb.id"
          + " ORDER BY pb.finished_at DESC NULLS LAST, pb.id DESC"
          + " LIMIT 1";

    /**
     * Write the report snapshot row at finalization time from the BatchFinalizeSupport advanced&gt;0 guard bit, taking effect atomically in the same transaction.
     *
     * @param batchId Batch ID.
     * @param batchStatus finalization target status (6 Completed / 7 Partially Failed; snapshot redundancy lets the report page self-explain).
     */
    public void writeSnapshot(long batchId, int batchStatus) {
        Map<String, List<Double>> mediansByMetric = new LinkedHashMap<>();
        long sampleCountSum = 0L;
        boolean hasSampleCount = false;
        for (Map<String, Object> row : jdbcTemplate.queryForList(MOBILE_TASK_SQL, batchId)) {
            Object metricsText = row.get("metrics_json");
            if (metricsText != null) {
                JsonNode metrics = readTree(metricsText.toString());
                Iterator<Map.Entry<String, JsonNode>> fields = metrics.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    JsonNode medianNode = field.getValue().path("median");
                    if (medianNode.isNumber()) {
                        mediansByMetric
                                .computeIfAbsent(field.getKey(), k -> new ArrayList<>())
                                .add(medianNode.asDouble());
                    }
                }
            }
            Object sampleCount = row.get("sample_count");
            if (sampleCount != null) {
                sampleCountSum += Long.parseLong(sampleCount.toString());
                hasSampleCount = true;
            }
        }
        String sql = SNAPSHOT_SQL.replace("__RATE_EXPR__", PassRateSql.RATE_EXPR);
        if (mediansByMetric.isEmpty()) {
            // Honest absence: without any reported metric both rollup columns stay NULL.
            jdbcTemplate.update(sql,
                    new SqlParameterValue(Types.OTHER, null),
                    new SqlParameterValue(Types.BIGINT, null),
                    batchStatus, batchId);
            return;
        }
        Long prevBatchId = null;
        JsonNode prevMetrics = null;
        List<Map<String, Object>> prevRows = jdbcTemplate.queryForList(PREV_BATCH_SQL, batchId);
        if (!prevRows.isEmpty()) {
            Object prevId = prevRows.get(0).get("batch_id");
            prevBatchId = prevId == null ? null : ((Number) prevId).longValue();
            Object prevJson = prevRows.get(0).get("mobile_perf_json");
            if (prevJson != null) {
                prevMetrics = readTree(prevJson.toString()).path("metrics");
            }
        }
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode metricsNode = root.putObject("metrics");
        for (Map.Entry<String, List<Double>> entry : mediansByMetric.entrySet()) {
            double median = medianOf(entry.getValue());
            ObjectNode metric = metricsNode.putObject(entry.getKey());
            metric.put("median", round2(median));
            metric.put("taskCount", entry.getValue().size());
            if (prevMetrics != null) {
                JsonNode prevMetric = prevMetrics.path(entry.getKey());
                if (prevMetric.path("median").isNumber()) {
                    double prevMedian = prevMetric.path("median").asDouble();
                    metric.put("prevMedian", round2(prevMedian));
                    metric.put("delta", round2(median - prevMedian));
                }
            }
        }
        if (prevBatchId != null) {
            root.put("prevBatchId", prevBatchId);
        }
        if (hasSampleCount) {
            root.put("sampleCount", sampleCountSum);
        }
        jdbcTemplate.update(sql,
                new SqlParameterValue(Types.OTHER, root.toString()),
                new SqlParameterValue(Types.BIGINT, prevBatchId),
                batchStatus, batchId);
    }

    /** Median of the per-task readings: odd count takes the middle, even count averages the middle pair. */
    private double medianOf(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }

    private double round2(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private JsonNode readTree(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("mobilePerfSummary payload is not valid json: " + json, e);
        }
    }
}
