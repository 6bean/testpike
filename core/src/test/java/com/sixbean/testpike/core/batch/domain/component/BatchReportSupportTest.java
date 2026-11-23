package com.sixbean.testpike.core.batch.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.CoreApplication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Batch report snapshot rollup tests for the mobile sampling numbers.
 * <p>Runs against the real dev database of this chain slot (strict testing:
 * no mocks). Covers the batch-level median rollup read from
 * {@code tp_exec_task.result_ext -> mobilePerfSummary}, the previous-cycle
 * lineage (nearest earlier finalized batch of the same project and
 * environment) with prevMedian/delta, honest absence for non-mobile batches
 * (NULL column, never zero-filled) and re-finalization idempotency.
 * <p>Fixtures insert their own rows and clean up by recorded ids; factory
 * baseline tables are never truncated.
 */
@SpringBootTest(classes = CoreApplication.class)
class BatchReportSupportTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private BatchReportSupport batchReportSupport;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> createdTasks = new ArrayList<>();
    private final List<Long> createdBatches = new ArrayList<>();
    private long projectId;

    @BeforeEach
    void createFixtureProject() {
        // A dedicated project keeps the cycle key (project + environment) exclusive
        // to these fixtures, so previous-cycle resolution can never be perturbed by
        // real factory or other-suite rows.
        String code = ("RT" + java.util.UUID.randomUUID().toString().replace("-", ""))
                .substring(0, 20);
        jdbcTemplate.update("INSERT INTO tp_project (name, code) VALUES ('rollup-fixture-project', ?)", code);
        projectId = jdbcTemplate.queryForObject("SELECT max(id) FROM tp_project", Long.class);
    }

    @AfterEach
    void cleanup() {
        for (long taskId : createdTasks) {
            jdbcTemplate.update("DELETE FROM tp_exec_task WHERE id = ?", taskId);
        }
        for (long batchId : createdBatches) {
            jdbcTemplate.update("DELETE FROM tp_batch_report WHERE batch_id = ?", batchId);
            jdbcTemplate.update("DELETE FROM tp_exec_batch WHERE id = ?", batchId);
        }
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
        createdTasks.clear();
        createdBatches.clear();
    }

    /** Finalize-equivalent batch row; env stays NULL so both fixtures share one cycle key. */
    private long insertBatch() {
        jdbcTemplate.update("INSERT INTO tp_exec_batch (project_id, name, status)"
                        + " VALUES (?, 'rollup-fixture-batch', 6)",
                projectId);
        long id = jdbcTemplate.queryForObject("SELECT max(id) FROM tp_exec_batch", Long.class);
        createdBatches.add(id);
        return id;
    }

    /** Task carrying a mobilePerfSummary rollup (same shape the executor trailing key lands). */
    private long insertMobileTask(long batchId, String metric, double median, int rowCount) {
        String ext = "{\"mobilePerfSummary\":{\"metrics\":{\"" + metric + "\":"
                + "{\"median\":" + median + ",\"count\":" + rowCount + "}},"
                + "\"sampleCount\":" + rowCount + "}}";
        jdbcTemplate.update("INSERT INTO tp_exec_task (project_id, case_id, batch_id, status, result_ext)"
                        + " VALUES (?, 0, ?, 3, ?::jsonb)",
                projectId, batchId, ext);
        long id = jdbcTemplate.queryForObject("SELECT max(id) FROM tp_exec_task", Long.class);
        createdTasks.add(id);
        return id;
    }

    /** Task without any mobile key — must never pollute the rollup. */
    private void insertPlainTask(long batchId) {
        jdbcTemplate.update("INSERT INTO tp_exec_task (project_id, case_id, batch_id, status)"
                + " VALUES (?, 0, ?, 3)", projectId, batchId);
        long id = jdbcTemplate.queryForObject("SELECT max(id) FROM tp_exec_task", Long.class);
        createdTasks.add(id);
    }

    private JsonNode reportMobileJson(long batchId) {
        String raw = jdbcTemplate.queryForObject(
                "SELECT mobile_perf_json::text FROM tp_batch_report WHERE batch_id = ?",
                String.class, batchId);
        try {
            return raw == null ? null : MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException("mobile_perf_json is not valid json: " + raw, e);
        }
    }

    @Test
    void rollupMedianAcrossTasksAndPrevCycleDelta() {
        long first = insertBatch();
        insertMobileTask(first, "cpuPct", 10.0, 5);
        insertMobileTask(first, "cpuPct", 30.0, 7);
        batchReportSupport.writeSnapshot(first, 6);

        JsonNode firstJson = reportMobileJson(first);
        assertNotNull(firstJson, "mobile rollup must land for a batch with reported metrics");
        JsonNode firstCpu = firstJson.path("metrics").path("cpuPct");
        assertEquals(20.0, firstCpu.path("median").asDouble(), 1e-9,
                "batch median = median across the two reported task medians");
        assertEquals(2, firstCpu.path("taskCount").asInt());
        assertTrue(firstJson.path("prevBatchId").isMissingNode() || firstJson.path("prevBatchId").isNull(),
                "first batch of a cycle has no previous lineage");
        Long firstPrev = jdbcTemplate.queryForObject(
                "SELECT prev_batch_id FROM tp_batch_report WHERE batch_id = ?",
                Long.class, first);
        assertNull(firstPrev);

        long second = insertBatch();
        insertMobileTask(second, "cpuPct", 50.0, 9);
        batchReportSupport.writeSnapshot(second, 6);

        JsonNode secondJson = reportMobileJson(second);
        assertEquals(first, secondJson.path("prevBatchId").asLong(),
                "previous-cycle anchor = nearest earlier finalized batch of the same cycle key");
        JsonNode secondCpu = secondJson.path("metrics").path("cpuPct");
        assertEquals(50.0, secondCpu.path("median").asDouble(), 1e-9);
        assertEquals(20.0, secondCpu.path("prevMedian").asDouble(), 1e-9);
        assertEquals(30.0, secondCpu.path("delta").asDouble(), 1e-9);
    }

    @Test
    void batchWithoutMobileMetricsKeepsColumnNullAndCountsUnaffected() {
        long batchId = insertBatch();
        insertPlainTask(batchId);
        insertPlainTask(batchId);
        batchReportSupport.writeSnapshot(batchId, 6);

        assertNull(reportMobileJson(batchId),
                "no reported mobile metrics must stay honest absence (NULL), never an empty object");
        Integer total = jdbcTemplate.queryForObject(
                "SELECT total FROM tp_batch_report WHERE batch_id = ?", Integer.class, batchId);
        assertEquals(2, total, "ordinary snapshot columns are unaffected by the rollup");
    }

    @Test
    void metricMissingInPreviousCycleHasNoDeltaFields() {
        long first = insertBatch();
        insertMobileTask(first, "cpuPct", 10.0, 5);
        batchReportSupport.writeSnapshot(first, 6);

        long second = insertBatch();
        insertMobileTask(second, "pssMb", 100.0, 4);
        batchReportSupport.writeSnapshot(second, 6);

        JsonNode pss = reportMobileJson(second).path("metrics").path("pssMb");
        assertEquals(100.0, pss.path("median").asDouble(), 1e-9);
        assertTrue(pss.path("prevMedian").isMissingNode(),
                "a metric the previous batch never reported has no prev comparison (no zero-fill)");
        assertTrue(pss.path("delta").isMissingNode());
    }

    @Test
    void reFinalizationOverwritesRollupRowWithoutStacking() {
        long batchId = insertBatch();
        insertMobileTask(batchId, "cpuPct", 10.0, 5);
        batchReportSupport.writeSnapshot(batchId, 6);
        insertMobileTask(batchId, "cpuPct", 30.0, 5);
        batchReportSupport.writeSnapshot(batchId, 7);

        Integer rowCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_batch_report WHERE batch_id = ?", Integer.class, batchId);
        assertEquals(1, rowCount, "uk(batch_id) keeps exactly one snapshot row per batch");
        assertEquals(20.0, reportMobileJson(batchId).path("metrics").path("cpuPct")
                .path("median").asDouble(), 1e-9,
                "re-finalization recomputes the rollup from the current task set");
        Integer status = jdbcTemplate.queryForObject(
                "SELECT batch_status FROM tp_batch_report WHERE batch_id = ?", Integer.class, batchId);
        assertEquals(7, status);
    }
}
