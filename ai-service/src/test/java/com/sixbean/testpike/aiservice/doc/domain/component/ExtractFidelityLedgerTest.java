package com.sixbean.testpike.aiservice.doc.domain.component;

import com.sixbean.testpike.aiservice.doc.domain.component.ExtractFidelityLedger.Entry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Extraction fidelity ledger : one mechanical row per
 * (document, generation) naming what the extraction plane actually read versus
 * what the source document holds — injected tokens, source tokens, retention
 * %, node count, distinct chapters, anchor fill rate, estimate-vs-provider
 * usage drift and the per-item billed output coefficients that the
 * output-budget rule of / back-derives from. Zero model calls: every
 * number is either passed in by the extraction run or summed from the single
 * call seam (tp_ai_call_log plane), so the acceptance posture "ledger equals
 * call log with zero difference" holds.
 */
class ExtractFidelityLedgerTest {

    private static final long DOC = 8639L;
    private static final String TOKEN = "gen-fidelity-001";
    private static final String TASK_TYPE = "doc-extract";

    /** Plane vocabulary in test shape — the production seam passes its own
     *  tp_ai_call_log.function constants through PlaneLabels. */
    private static final PlaneLabels LABELS =
            new PlaneLabels("extract-plane", "summary-plane", "gap-plane");

    private static Entry entry(ExtractFidelityMeter meter, int sourceTokens, int chunkCount,
                               int nodeCount, int chapters, int items, int anchored,
                               int estimateTokens) {
        return ExtractFidelityLedger.entry(DOC, TOKEN, TASK_TYPE, sourceTokens, chunkCount, nodeCount,
                chapters, meter, items, anchored, estimateTokens, LABELS);
    }

    // ---- pure accounting side (no door, no model) ----

    @Test
    void meter_sumsUsagePerFunction_forCallLogReconciliation() {
        ExtractFidelityMeter meter = new ExtractFidelityMeter();
        meter.record(LABELS.extractPlane(), 10_000, 20_000, 5_000);
        meter.record(LABELS.extractPlane(), 4_000, 9_000, 3_000);
        meter.record(LABELS.summaryPlane(), 94_175, 2_000, 0);
        // reconciliation face = every booked call, zero difference by construction
        assertEquals(3, meter.calls());
        assertEquals(108_175, meter.promptTokensTotal());
        assertEquals(31_000, meter.completionTokensTotal());
        // extract-plane face = only the calls that read the injection source
        assertEquals(14_000, meter.promptOf(LABELS.extractPlane()));
        assertEquals(29_000, meter.completionOf(LABELS.extractPlane()));
        assertEquals(8_000, meter.reasoningOf(LABELS.extractPlane()));
    }

    @Test
    void entry_computesRetentionAnchorsAndDrift() {
        ExtractFidelityMeter meter = new ExtractFidelityMeter();
        meter.record(LABELS.extractPlane(), 10_000, 20_000, 5_000);
        meter.record(LABELS.extractPlane(), 4_000, 9_000, 3_000);
        meter.record(LABELS.gapPlane(), 1_000, 500, 100);
        meter.record(LABELS.summaryPlane(), 2, 2, 2);
        Entry entry = entry(meter,
                121_375,   // source tokens of the whole document
                642,       // chunks
                16,        // nodes (groups or final buckets)
                460,       // distinct chapters
                300, 288,  // items / anchor-filled items
                14_500);   // estimate-side face of the same injected package
        // injection face = final extract + reconcile top-up calls (both read chunks)
        assertEquals(15_000, entry.injectedTokens());
        assertEquals(12.36, entry.retentionPercent(), 0.001);
        assertEquals(96.00, entry.anchorRatePercent(), 0.001);
        assertEquals(-3.33, entry.estDriftPercent(), 0.001);
        assertEquals(300, entry.itemCount());
        assertEquals(1, entry.reconcileCalls());
        assertEquals(1, entry.summaryCalls());
    }

    @Test
    void entry_perItemCoefficients_plainJsonAndThinkingInclusive() {
        ExtractFidelityMeter meter = new ExtractFidelityMeter();
        meter.record(LABELS.extractPlane(), 10_000, 29_749, 6_000);
        meter.record(LABELS.extractPlane(), 14_167, 19_374, 4_000);
        Entry entry = entry(meter, 25_397, 144, 2, 90, 268, 267, 24_000);
        // billed = prompt+completion over the extract plane, per produced item
        assertEquals(273, entry.billedTokensPerItem());
        // plain JSON = completion minus reasoning, per produced item
        assertEquals(146, entry.plainJsonTokensPerItem());
    }

    @Test
    void entry_zeroItemCount_zeroSource_keepsNumbersHonest() {
        // never invent rates: empty denominator answers 0, not a fabricated green
        Entry entry = entry(new ExtractFidelityMeter(), 0, 0, 0, 0, 0, 0, 0);
        assertEquals(0.0, entry.retentionPercent(), 0.0001);
        assertEquals(0.0, entry.anchorRatePercent(), 0.0001);
        assertEquals(0.0, entry.estDriftPercent(), 0.0001);
        assertEquals(0, entry.plainJsonTokensPerItem());
        assertEquals(0, entry.billedTokensPerItem());
    }

    @Test
    void meter_isSafeAcrossBucketThreads() throws Exception {
        ExtractFidelityMeter meter = new ExtractFidelityMeter();
        List<Thread> threads = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Thread t = new Thread(() -> {
                for (int k = 0; k < 250; k++) {
                    meter.record(LABELS.extractPlane(), 1, 2, 0);
                }
            });
            threads.add(t);
            t.start();
        }
        for (Thread t : threads) {
            t.join();
        }
        assertEquals(2000, meter.calls());
        assertEquals(2000, meter.promptOf(LABELS.extractPlane()));
        assertEquals(4000, meter.completionOf(LABELS.extractPlane()));
    }

    // ---- storage door (mocked jdbc = infrastructure isolation, L2 posture) ----

    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final ExtractFidelityLedgerStore underTest = new ExtractFidelityLedgerStore(jdbc);

    private static Entry sampleEntry() {
        ExtractFidelityMeter meter = new ExtractFidelityMeter();
        meter.record(LABELS.extractPlane(), 4_924, 34_203, 0);
        return entry(meter, 121_375, 642, 2, 2, 50, 0, 5_000);
    }

    @Test
    void firstRow_insertsEveryFidelityColumn() {
        when(jdbc.query(contains("SELECT"), any(MapSqlParameterSource.class),
                any(org.springframework.jdbc.core.RowMapper.class)))
                .thenReturn(List.of());
        assertTrue(underTest.upsertIfChanged(sampleEntry()));
        ArgumentCaptor<MapSqlParameterSource> captor =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).update(contains("INSERT INTO tp_doc_extract_fidelity"), captor.capture());
        MapSqlParameterSource row = captor.getValue();
        assertEquals(DOC, ((Number) row.getValue("refId")).longValue());
        assertEquals(TOKEN, row.getValue("runId"));
        assertEquals(TASK_TYPE, row.getValue("taskType"));
        assertEquals(642, ((Number) row.getValue("chunkCount")).intValue());
        assertEquals(2, ((Number) row.getValue("nodeCount")).intValue());
        assertEquals(4924, ((Number) row.getValue("injectedTokens")).intValue());
        assertEquals(121375, ((Number) row.getValue("sourceTokens")).intValue());
        assertEquals(50, ((Number) row.getValue("itemCount")).intValue());
        assertEquals(0.0, ((Number) row.getValue("anchorRatePercent")).doubleValue(), 0.001);
    }

    @Test
    void replayedIdenticalEntry_writesNothing() {
        Entry entry = sampleEntry();
        when(jdbc.query(contains("SELECT"), any(MapSqlParameterSource.class),
                any(org.springframework.jdbc.core.RowMapper.class)))
                .thenReturn(List.of(entry));
        assertFalse(underTest.upsertIfChanged(entry));
        verify(jdbc, never()).update(contains("INSERT"), any(MapSqlParameterSource.class));
        verify(jdbc, never()).update(contains("UPDATE"), any(MapSqlParameterSource.class));
    }

    @Test
    void realChange_updatesRowAndBumpsRevision() {
        Entry older = entry(new ExtractFidelityMeter(), 121_375, 642, 2, 2, 50, 0, 5_000);
        when(jdbc.query(contains("SELECT"), any(MapSqlParameterSource.class),
                any(org.springframework.jdbc.core.RowMapper.class)))
                .thenReturn(List.of(older));
        assertTrue(underTest.upsertIfChanged(sampleEntry()));
        verify(jdbc).update(contains("UPDATE tp_doc_extract_fidelity"),
                any(MapSqlParameterSource.class));
    }

    @Test
    void doorOutage_answersNoRow_insteadOfThrowing() {
        when(jdbc.query(contains("SELECT"), any(MapSqlParameterSource.class),
                any(org.springframework.jdbc.core.RowMapper.class)))
                .thenThrow(new RuntimeException("door down"));
        assertNull(underTest.find(DOC, TOKEN));
        // a write against an unreachable door never fails the extraction run
        when(jdbc.update(anyString(), any(MapSqlParameterSource.class)))
                .thenThrow(new RuntimeException("door down"));
        assertFalse(underTest.upsertIfChanged(sampleEntry()));
    }

    // ---- : per-plane durability anchors + readable reject note ----

    /** The insert door must book the two domain anchor columns and bind the
     *  qa-gen job row through the run token — the durability face that kept
     *  the question-generation account from cascading away with its
     *  temporary document (9 done jobs vs 1 surviving row). */
    @Test
    void insert_booksPerPlaneAnchorColumns_qaPlaneBindsJobRowThroughRunToken() {
        when(jdbc.query(contains("SELECT"), any(MapSqlParameterSource.class),
                any(org.springframework.jdbc.core.RowMapper.class)))
                .thenReturn(List.of());
        assertTrue(underTest.upsertIfChanged(sampleEntry()));
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> rowCaptor =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).update(sqlCaptor.capture(), rowCaptor.capture());
        String sql = sqlCaptor.getValue();
        assertTrue(sql.contains("ref_document_id"),
                "insert must book the document-plane anchor column: " + sql);
        assertTrue(sql.contains("ref_qa_job_id"),
                "insert must book the job-plane anchor column: " + sql);
        assertTrue(sql.contains("tp_qa_gen_job"),
                "job anchor must resolve through the qa job ledger: " + sql);
        assertTrue(sql.contains("j.run_token = :runId"),
                "job anchor must bind by the run token: " + sql);
        assertTrue(sql.contains("CASE WHEN :taskType = 'qa-gen' THEN NULL ELSE :refId END"),
                "document anchor stays the reference row outside the qa plane: " + sql);
        assertEquals(TOKEN, rowCaptor.getValue().getValue("runId"));
    }

    /** Single-source reject note format: plane, reference row, run token,
     *  root cause and the consequence for the reader. */
    @Test
    void rejectNote_namesPlaneRefRunCauseAndConsequence() {
        String note = ExtractFidelityLedger.rejectNote("qa-gen", 9067L, "tok-1",
                new IllegalStateException("door exploded"));
        assertTrue(note.contains("plane=qa-gen"), note);
        assertTrue(note.contains("refId=9067"), note);
        assertTrue(note.contains("run=tok-1"), note);
        assertTrue(note.contains("根因=IllegalStateException: door exploded"), note);
        assertTrue(note.contains("后果="), note);
    }

    /** Refusal face (bidirectional to the format above): every booked door
 * that loses the row logs the readable note, never a collapsed
 * bookings behind this defect. The store keeps a static logger, so the
 * capture uses a logback ListAppender (same posture as the fanout
 * suite precedent for static-logger components). */
    @Test
    void refusedBooking_logsReadableReject_neverCollapsedWarning() {
        ch.qos.logback.classic.Logger storeLogger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
                        .getLogger(ExtractFidelityLedgerStore.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> probe =
                new ch.qos.logback.core.read.ListAppender<>();
        probe.start();
        storeLogger.addAppender(probe);
        try {
            when(jdbc.query(contains("SELECT"), any(MapSqlParameterSource.class),
                    any(org.springframework.jdbc.core.RowMapper.class)))
                    .thenReturn(List.of());
            when(jdbc.update(anyString(), any(MapSqlParameterSource.class)))
                    .thenThrow(new RuntimeException("fk refused"));
            assertFalse(underTest.upsertIfChanged(sampleEntry()));
            assertFalse(underTest.recordIfAbsent(sampleEntry()));
        } finally {
            storeLogger.detachAppender(probe);
        }
        assertEquals(2, probe.list.size(), "both booking doors must report their refusal");
        for (ch.qos.logback.classic.spi.ILoggingEvent event : probe.list) {
            String line = event.getFormattedMessage();
            assertTrue(line.contains("plane="), line);
            assertTrue(line.contains("根因="), line);
            assertTrue(line.contains("后果="), line);
            assertFalse(line.contains("不影响"),
                    "collapsed reject wording is banned by T649: " + line);
        }
    }
}
