package com.sixbean.testpike.aiservice.taskstream.domain.component;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Storage contract of the process event ledger (tp_ai_task_event): long-running
 * AI jobs append one row per fine process step (chunk start, per-chunk call
 * with the visible reasoning summary and token counts, parse totals, retries,
 * failure causes, grouping milestones) so an operator can watch the run like a
 * live log instead of a black box.
 * <p>Three invariants are pinned here:
 * <ul>
 *   <li>append-only write keyed by (jobKind, jobRef, runToken); unknown step
 *       levels never reach the door — the viewer's level filter is a closed
 *       set (thinking / step / error);</li>
 *   <li>the reader is a strict cursor window (id &gt; cursor, ascending), so
 *       replaying the same cursor re-shows exactly the same next window —
 *       nothing is skipped, nothing duplicates;</li>
 *   <li>the ledger is advisory bookkeeping: a door outage must never fail the
 *       job that writes it, and must answer an empty window, not an error.</li>
 * </ul>
 */
class TaskProcessEventStoreTest {

    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final TaskProcessEventStore underTest = new TaskProcessEventStore(jdbc);

    private static final String KIND = "doc-extract";
    private static final String REF = "document:8491";
    private static final String TOKEN = "gen-token-001";

    @Test
    void record_landsAppendOnlyRow_keyedByJobIdentity() {
        underTest.record(KIND, REF, TOKEN, "thinking", "分片 3/12 开始：命中预算 0.6，先摘要再抽取",
                "{\"chunk\":3,\"tokens\":1204}");
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> params =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).update(sql.capture(), params.capture());
        assertTrue(sql.getValue().contains("INSERT INTO tp_ai_task_event"), sql.getValue());
        MapSqlParameterSource landed = params.getValue();
        assertEquals(KIND, landed.getValue("jobKind"));
        assertEquals(REF, landed.getValue("jobRef"));
        assertEquals(TOKEN, landed.getValue("runToken"));
        assertEquals("thinking", landed.getValue("level"));
        assertEquals("分片 3/12 开始：命中预算 0.6，先摘要再抽取", landed.getValue("summary"));
        assertEquals("{\"chunk\":3,\"tokens\":1204}", landed.getValue("detail"));
    }

    @Test
    void record_overlongSummary_truncatesToColumnWidth_neverDropsTheRow() {
        underTest.record(KIND, REF, TOKEN, "step", "长".repeat(600), null);
        ArgumentCaptor<MapSqlParameterSource> params =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).update(anyString(), params.capture());
        String summary = (String) params.getValue().getValue("summary");
        assertEquals(512, summary.length(),
                "an overlong summary must be shortened to the column width, not silently lost");
    }

    @Test
    void record_unknownLevelOrBlankSummary_isRejectedBeforeTheDoor() {
        underTest.record(KIND, REF, TOKEN, "gossip", "随便写的级别", null);
        underTest.record(KIND, REF, TOKEN, "step", "   ", null);
        verifyNoInteractions(jdbc);
    }

    @Test
    void record_doorDown_neverThrowsIntoTheJob() {
        when(jdbc.update(anyString(), any(MapSqlParameterSource.class)))
                .thenThrow(new RuntimeException("door down"));
        assertDoesNotThrow(() ->
                underTest.record(KIND, REF, TOKEN, "error", "第 5 片解析失败：上游超时", null));
    }

    @Test
    void fetchSince_readsStrictWindowAfterCursor_ascendingForIdempotentReplay() {
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of());
        underTest.fetchSince(KIND, REF, TOKEN, 100L, 200);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> params =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).query(sql.capture(), params.capture(), any(RowMapper.class));
        String statement = sql.getValue();
        assertTrue(statement.contains("id > :cursor"),
                "cursor replay must open the window strictly after the seen row: " + statement);
        assertTrue(statement.contains("ORDER BY id ASC"), statement);
        assertEquals(100L, params.getValue().getValue("cursor"));
        assertEquals(200, params.getValue().getValue("limit"));
    }

    @Test
    void fetchSince_mapsRowsToEvents_cursorIsTheStreamRowId() {
        LocalDateTime when = LocalDateTime.of(2026, 11, 9, 10, 20, 0);
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenAnswer(invocation -> {
                    RowMapper<TaskProcessEvent> mapper = invocation.getArgument(2);
                    java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
                    when(rs.getLong("id")).thenReturn(101L);
                    when(rs.getString("level")).thenReturn("step");
                    when(rs.getString("summary")).thenReturn("封组 2/5 完成");
                    when(rs.getString("detail")).thenReturn(null);
                    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.valueOf(when));
                    return List.of(mapper.mapRow(rs, 0));
                });
        List<TaskProcessEvent> window = underTest.fetchSince(KIND, REF, TOKEN, 100L, 200);
        assertEquals(1, window.size());
        TaskProcessEvent event = window.get(0);
        assertEquals(101L, event.cursor());
        assertEquals("step", event.level());
        assertEquals("封组 2/5 完成", event.summary());
        assertEquals(when, event.landedAt());
    }

    @Test
    void fetchSince_doorDown_answersEmptyWindow_notAnError() {
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenThrow(new RuntimeException("door down"));
        List<TaskProcessEvent> window = assertDoesNotThrow(
                () -> underTest.fetchSince(KIND, REF, TOKEN, 0L, 200));
        assertTrue(window.isEmpty());
        verify(jdbc, never()).update(anyString(), any(MapSqlParameterSource.class));
    }

    // ---- run-history aggregation door (revisit entry of the tail window) ----

    @Test
    void listRuns_groupsByRunToken_newestFirst_blankTokensExcluded_limitPassedThrough() {
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of());
        underTest.listRuns(KIND, REF, 20);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> params =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).query(sql.capture(), params.capture(), any(RowMapper.class));
        String statement = sql.getValue();
        assertTrue(statement.contains("GROUP BY run_token"),
                "the run list must aggregate one row per run token: " + statement);
        assertTrue(statement.contains("ORDER BY MAX(id) DESC"),
                "newest run first — the viewer lists history, not a replay stream: " + statement);
        assertTrue(statement.contains("LIMIT :limit"), statement);
        assertTrue(statement.contains("run_token IS NOT NULL")
                        && statement.contains("run_token <> ''"),
                "rows landed without a run token must never show as a run: " + statement);
        assertEquals(KIND, params.getValue().getValue("jobKind"));
        assertEquals(REF, params.getValue().getValue("jobRef"));
        assertEquals(20, params.getValue().getValue("limit"));
    }

    @Test
    void listRuns_mapsRowsToRunSummaries_terminalVerdictCarriesErrorRows() {
        LocalDateTime opened = LocalDateTime.of(2026, 11, 22, 9, 0, 0);
        LocalDateTime last = LocalDateTime.of(2026, 11, 22, 9, 41, 0);
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenAnswer(invocation -> {
                    RowMapper<TaskEventRun> mapper = invocation.getArgument(2);
                    java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
                    when(rs.getString("run_token")).thenReturn("run-77");
                    when(rs.getLong("event_count")).thenReturn(31L);
                    when(rs.getTimestamp("first_at")).thenReturn(Timestamp.valueOf(opened));
                    when(rs.getTimestamp("last_at")).thenReturn(Timestamp.valueOf(last));
                    when(rs.getInt("error_rows")).thenReturn(1);
                    return List.of(mapper.mapRow(rs, 0));
                });
        List<TaskEventRun> runs = underTest.listRuns(KIND, REF, 20);
        assertEquals(1, runs.size());
        TaskEventRun run = runs.get(0);
        assertEquals("run-77", run.runToken());
        assertEquals(31L, run.events());
        assertEquals(opened, run.firstAt());
        assertEquals(last, run.lastAt());
        assertTrue(run.hasError(),
                "a run with error rows must answer hasError so the list shows the failed badge");
    }

    @Test
    void listRuns_doorDown_answersEmptyList_notAnError() {
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenThrow(new RuntimeException("door down"));
        List<TaskEventRun> runs = assertDoesNotThrow(() -> underTest.listRuns(KIND, REF, 20));
        assertTrue(runs.isEmpty());
    }
}
