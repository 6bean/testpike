package com.sixbean.testpike.aiservice.doc.domain.component;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DD batch stage-row storage contract of the extraction stage
 * ledger (tp_doc_extract_stage). Every stage step of one generation (grouping
 * i/n, final bucket j/m, reconcile round) lands one row through the same door
 * as the coverage ledger; a replayed identical label writes nothing (the
 * IS DISTINCT FROM guard, R28 zero-write replay); a door outage never fails
 * the extraction itself — stage bookkeeping is advisory, the run must not
 * hinge on it.
 */
class DocExtractStageLedgerStoreTest {

    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final DocExtractLedgerStore underTest = new DocExtractLedgerStore(jdbc);

    private static final long DOC = 7L;
    private static final String TOKEN = "gen-token-e2e-001";

    @Test
    void recordStage_landsOneRowPerStep_withZeroWriteReplayGuard() {
        underTest.recordStage(DOC, TOKEN, "group-2", "封组 2/5");
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> params =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc, times(2)).update(sql.capture(), params.capture());
        int stageIdx = -1;
        for (int i = 0; i < sql.getAllValues().size(); i++) {
            if (sql.getAllValues().get(i).contains("tp_doc_extract_stage")) {
                stageIdx = i;
            }
        }
        assertTrue(stageIdx >= 0, "stage row must still be landed: " + sql.getAllValues());
        String statement = sql.getAllValues().get(stageIdx);
        assertTrue(statement.contains("tp_doc_extract_stage"), statement);
        assertTrue(statement.contains("ON CONFLICT"), statement);
        assertTrue(statement.contains("IS DISTINCT FROM"),
                "a replayed identical stage label must not bump updated_at: " + statement);
        assertTrue(params.getValue().getValue("code").equals("group-2"));
        assertTrue(params.getValue().getValue("label").equals("封组 2/5"));
    }

    @Test
    void recordStage_doorDown_neverThrowsIntoTheExtraction() {
        when(jdbc.update(anyString(), any(MapSqlParameterSource.class)))
                .thenThrow(new RuntimeException("door down"));
        assertDoesNotThrow(() -> underTest.recordStage(DOC, TOKEN, "bucket-1", "最终桶 1/3"));
    }

    /**
 * EE batch golden-window evidence: a 642-block grouped run is alive
 * for longer than the stall window while its stage rows keep landing, but
 * tp_document.updated_at stayed at dispatch time, so the stall sweeper
 * reclaimed the healthy run mid-flight (status 3 to 1 at 31 minutes).
 * Every stage boundary must therefore heartbeat the task row, guarded to
 * the extracting status and this generation's token (a zombie of an older
 * generation never extends the lease of a fresh run).
 */
    @Test
    void recordStage_heartbeatsTaskRow_guardedByGeneration() {
        underTest.recordStage(DOC, TOKEN, "group-3", "封组 3/12");
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> params =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc, times(2)).update(sql.capture(), params.capture());
        int beat = -1;
        for (int i = 0; i < sql.getAllValues().size(); i++) {
            if (sql.getAllValues().get(i).contains("UPDATE tp_document")) {
                beat = i;
            }
        }
        assertTrue(beat >= 0,
                "a stage boundary must heartbeat tp_document: " + sql.getAllValues());
        String statement = sql.getAllValues().get(beat);
        assertTrue(statement.contains("updated_at = now()"), statement);
        assertTrue(statement.contains("status = 3"),
                "heartbeat must only touch extracting rows: " + statement);
        assertTrue(statement.contains("extract_token"),
                "heartbeat must be generation-guarded: " + statement);
        MapSqlParameterSource beatParams = params.getAllValues().get(beat);
        assertTrue(Long.valueOf(DOC).equals(beatParams.getValue("documentId")),
                String.valueOf(beatParams.getValue("documentId")));
        assertTrue(TOKEN.equals(beatParams.getValue("extractToken")));
    }

    /**
     * The two writes are independent best-effort doors: a heartbeat door
     * outage must not skip the stage row, and a stage door outage must not
     * skip the heartbeat (the failure order observed in production was the
     * stage insert FK-violating after the scratch project was swept).
     */
    @Test
    void recordStage_heartbeatDoorDown_stageRowStillLands() {
        when(jdbc.update(contains("UPDATE tp_document"), any(MapSqlParameterSource.class)))
                .thenThrow(new RuntimeException("heartbeat door down"));
        assertDoesNotThrow(() -> underTest.recordStage(DOC, TOKEN, "bucket-2", "最终桶 2/3"));
        verify(jdbc).update(contains("tp_doc_extract_stage"), any(MapSqlParameterSource.class));
    }
}
