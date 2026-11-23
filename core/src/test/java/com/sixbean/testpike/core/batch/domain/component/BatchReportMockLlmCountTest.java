package com.sixbean.testpike.core.batch.domain.component;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.batch.api.dto.BatchReportView;
import com.sixbean.testpike.core.batch.support.BatchReportFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Batch report mock-serving and AI-fallback count dimensions (honest-reporting
 * contract). Both dimensions are finalize-time snapshot columns on
 * tp_batch_report (V238): mock_task_cnt counts tasks whose result_ext carries a
 * non-empty mockNotes audit bucket — empty array / missing key never count — and
 * llm_task_cnt counts tasks whose mockNotes array holds at least one
 * source='llm' entry (the LLM fallback rung answers misses the stub library
 * cannot serve). The read path (BatchService.report) forwards snapshot values
 * verbatim; recomputation on read is banned (TC-I7-01 same-row discipline).
 * <p>The two dimensions must not bleed into each other: a stub-only task raises
 * mock_task_cnt alone, an llm-served task raises both (it is still a mock), and
 * a plain batch keeps both at zero with no drift.
 * <p>Real dev DB, no mocks (strict-testing-standards). Row builders/finalize/sweep
 * are shared through BatchReportFixture (single source).
 */
@SpringBootTest(classes = CoreApplication.class)
class BatchReportMockLlmCountTest {

    @Autowired
    private BatchReportFixture fixture;
    @Autowired
    private com.sixbean.testpike.core.batch.domain.service.BatchService batchService;

    @BeforeEach
    void setUp() {
        fixture.isolateSideChannels();
    }

    @AfterEach
    void tearDown() {
        fixture.sweepFixtureRows();
    }

    // ── ① snapshot counts only tasks with a non-empty mockNotes bucket ──

    @Test
    void finalize_counts_tasks_with_non_empty_mock_notes() {
        long pid = fixture.createProject("MC1");
        long c1 = fixture.createCase(pid, "case-a");
        long batchId = fixture.finalizedBatch(pid, "mock count", new Object[][]{{c1, 3}, {c1, 3}, {c1, 4}, {c1, 3}});
        fixture.setResultExt(batchId, 0, "{\"mockNotes\": [{\"path\": \"/api/a\", \"mode\": \"stub\"}]}");
        fixture.setResultExt(batchId, 1, "{\"mockNotes\": []}");          // empty bucket never counts
        fixture.setResultExt(batchId, 2, "{\"consoleViolations\": []}"); // no mockNotes key at all

        fixture.finalizeBatch(batchId);

        assertEquals(1, fixture.snapshotColumn(batchId, "mock_task_cnt"),
                "only the task with a non-empty mockNotes bucket counts into the snapshot");
    }

    // ── ② read path forwards the mock snapshot value, zero recomputation ──

    @Test
    void report_view_exposes_mock_task_count_from_snapshot() {
        long pid = fixture.createProject("MC2");
        long c1 = fixture.createCase(pid, "case-b");
        long batchId = fixture.finalizedBatch(pid, "mock count", new Object[][]{{c1, 3}, {c1, 3}});
        fixture.setResultExt(batchId, 0, "{\"mockNotes\": [{\"path\": \"/x\", \"mode\": \"record\"}]}");
        fixture.setResultExt(batchId, 1, "{\"mockNotes\": [{\"path\": \"/y\", \"mode\": \"stub\"}]}");

        fixture.finalizeBatch(batchId);
        // mutate evidence after finalize: the read path must still show the snapshot
        fixture.setResultExt(batchId, 0, "{\"mockNotes\": []}");

        BatchReportView view = batchService.report(batchId);
        assertEquals(2, view.mockTaskCount(),
                "report view = snapshot row verbatim (post-finalize edits never recompute)");
    }

    // ── ③ no mock tasks = 0, plain batches keep zero drift ──

    @Test
    void plain_batch_reports_zero_mock_count() {
        long pid = fixture.createProject("MC3");
        long c1 = fixture.createCase(pid, "case-c");
        long batchId = fixture.finalizedBatch(pid, "mock count", new Object[][]{{c1, 3}, {c1, 6}});

        fixture.finalizeBatch(batchId);

        assertEquals(0, fixture.snapshotColumn(batchId, "mock_task_cnt"));
        Map<String, Object> row = fixture.reportRow(batchId);
        assertEquals(2, ((Number) row.get("total")).intValue(), "existing counters untouched");
        assertEquals(0, batchService.report(batchId).mockTaskCount());
    }

    // ── ④ re-finalize overwrites, never stacks (uk(batch_id) contract) ──

    @Test
    void refinalize_updates_mock_count_without_stacking() {
        long pid = fixture.createProject("MC4");
        long c1 = fixture.createCase(pid, "case-d");
        long batchId = fixture.finalizedBatch(pid, "mock count", new Object[][]{{c1, 3}});
        fixture.setResultExt(batchId, 0, "{\"mockNotes\": [{\"path\": \"/z\", \"mode\": \"stub\"}]}");
        fixture.finalizeBatch(batchId);
        assertEquals(1, fixture.snapshotColumn(batchId, "mock_task_cnt"));

        fixture.finalizeBatch(batchId);
        assertEquals(1, fixture.snapshotColumn(batchId, "mock_task_cnt"), "second finalize = overwrite same row");
        Integer rows = fixture.jdbc().queryForObject(
                "SELECT count(*) FROM tp_batch_report WHERE batch_id = ?", Integer.class, batchId);
        assertEquals(1, rows);
    }

    // ── ⑤ an llm-served task raises both dimensions (still a mock) ──

    @Test
    void finalize_counts_tasks_with_llm_source_into_second_dimension() {
        long pid = fixture.createProject("LC1");
        long c1 = fixture.createCase(pid, "case-a");
        long batchId = fixture.finalizedBatch(pid, "llm count", new Object[][]{{c1, 3}, {c1, 3}, {c1, 3}});
        fixture.setResultExt(batchId, 0, "{\"mockNotes\": [{\"path\": \"/api/a\", \"mode\": \"stub\"}]}");
        fixture.setResultExt(batchId, 1, "{\"mockNotes\": [{\"path\": \"/api/b\", \"mode\": \"llm\","
                + " \"source\": \"llm\"}]}");
        fixture.setResultExt(batchId, 2, "{\"mockNotes\": []}");

        fixture.finalizeBatch(batchId);

        assertEquals(1, fixture.snapshotColumn(batchId, "llm_task_cnt"),
                "only the task carrying a source=llm audit row counts into llm_task_cnt");
        assertEquals(2, fixture.snapshotColumn(batchId, "mock_task_cnt"),
                "the llm-served task is still a mock: first dimension untouched");
    }

    // ── ⑥ stub-only evidence never raises the llm dimension ──

    @Test
    void stub_only_notes_do_not_count_into_llm_dimension() {
        long pid = fixture.createProject("LC2");
        long c1 = fixture.createCase(pid, "case-b");
        long batchId = fixture.finalizedBatch(pid, "llm count", new Object[][]{{c1, 3}, {c1, 4}});
        fixture.setResultExt(batchId, 0, "{\"mockNotes\": [{\"path\": \"/x\", \"mode\": \"stub\","
                + " \"source\": \"stub\"}]}");
        fixture.setResultExt(batchId, 1, "{\"mockNotes\": [{\"path\": \"/y\", \"mode\": \"record\"}]}");

        fixture.finalizeBatch(batchId);

        assertEquals(0, fixture.snapshotColumn(batchId, "llm_task_cnt"),
                "source=stub or missing source never matches the llm filter");
        assertEquals(2, fixture.snapshotColumn(batchId, "mock_task_cnt"));
    }

    // ── ⑦ read path forwards both snapshot values, zero recomputation ──

    @Test
    void report_view_exposes_llm_task_count_from_snapshot() {
        long pid = fixture.createProject("LC3");
        long c1 = fixture.createCase(pid, "case-c");
        long batchId = fixture.finalizedBatch(pid, "llm count", new Object[][]{{c1, 3}, {c1, 3}});
        fixture.setResultExt(batchId, 0, "{\"mockNotes\": [{\"path\": \"/z\", \"mode\": \"llm\","
                + " \"source\": \"llm\"}]}");

        fixture.finalizeBatch(batchId);
        // mutate evidence after finalize: the read path must still show the snapshot
        fixture.setResultExt(batchId, 0, "{\"mockNotes\": []}");

        BatchReportView view = batchService.report(batchId);
        assertEquals(1, view.llmTaskCount(),
                "report view = snapshot row verbatim (post-finalize edits never recompute)");
    }

    // ── ⑧ plain batch keeps both dimensions at zero, existing counters untouched ──

    @Test
    void plain_batch_reports_zero_llm_count() {
        long pid = fixture.createProject("LC4");
        long c1 = fixture.createCase(pid, "case-d");
        long batchId = fixture.finalizedBatch(pid, "llm count", new Object[][]{{c1, 3}, {c1, 6}});

        fixture.finalizeBatch(batchId);

        assertEquals(0, fixture.snapshotColumn(batchId, "llm_task_cnt"));
        Map<String, Object> row = fixture.reportRow(batchId);
        assertEquals(2, ((Number) row.get("total")).intValue(), "existing counters untouched");
        assertEquals(0, batchService.report(batchId).llmTaskCount());
    }
}
