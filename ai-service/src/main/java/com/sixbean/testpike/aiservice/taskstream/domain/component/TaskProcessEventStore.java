package com.sixbean.testpike.aiservice.taskstream.domain.component;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Storage door of tp_ai_task_event — the process event ledger of long-running
 * AI jobs .
 * <p>Every fine step of a job run (chunk call with its visible reasoning
 * summary and token counts, parsed totals, retries, failure causes, grouping
 * milestones) appends one row keyed by (jobKind, jobRef, runToken); the
 * coarse stage ledger of the extraction face keeps writing in parallel and is
 * NOT re-opened here.
 * <p>Both doors are advisory: a write outage never fails the job that reports
 * progress, and an unreachable read answers an empty window — the cursor of
 * the last event actually shown stays where it was, so the next poll replays
 * the same window instead of skipping content. The row id doubles as the
 * stream cursor (strictly increasing), which is what makes cursor replay
 * idempotent.
 * <p>Hard boundary: only the closed viewer filter levels (thinking / step /
 * error) land — the hidden chain-of-thought is never persisted here, only the
 * model's visible reasoning note, per the open-source redaction posture.
 */
@Component
public final class TaskProcessEventStore {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(TaskProcessEventStore.class);

    /** Closed set backing the viewer's level filter; anything else is rejected. */
    private static final java.util.Set<String> LEVELS =
            java.util.Set.of("thinking", "step", "error");

    private static final int SUMMARY_WIDTH = 512;

    private static final String INSERT_SQL =
            "INSERT INTO tp_ai_task_event"
                    + " (job_kind, job_ref, run_token, level, summary, detail)"
                    + " VALUES (:jobKind, :jobRef, :runToken, :level, :summary, :detail)";

    private static final String FIND_SQL =
            "SELECT id, level, summary, detail, created_at FROM tp_ai_task_event"
                    + " WHERE job_kind = :jobKind AND job_ref = :jobRef"
                    + " AND run_token = :runToken AND id > :cursor"
                    + " ORDER BY id ASC LIMIT :limit";

    private final NamedParameterJdbcTemplate jdbc;

    public TaskProcessEventStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Append one process step of one job run. Unknown levels and blank
     * summaries are rejected before the door (a mislabelled row would poison
     * the viewer's filter); an overlong summary is truncated to the column
     * width instead of dropped; a door outage is logged and swallowed —
     * progress bookkeeping must never fail the job itself.
     */
    public void record(String jobKind, String jobRef, String runToken,
                       String level, String summary, String detailJson) {
        if (level == null || !LEVELS.contains(level)) {
            log.warn("过程事件级别不在筛选闭集内，丢弃不入库 jobKind={} level={}", jobKind, level);
            return;
        }
        if (summary == null || summary.isBlank()) {
            log.warn("过程事件缺少可读摘要，丢弃不入库 jobKind={} jobRef={}", jobKind, jobRef);
            return;
        }
        String landedSummary = summary.length() > SUMMARY_WIDTH
                ? summary.substring(0, SUMMARY_WIDTH)
                : summary;
        try {
            jdbc.update(INSERT_SQL, new MapSqlParameterSource()
                    .addValue("jobKind", jobKind)
                    .addValue("jobRef", jobRef)
                    .addValue("runToken", runToken)
                    .addValue("level", level)
                    .addValue("summary", landedSummary)
                    .addValue("detail", detailJson));
        } catch (RuntimeException doorDown) {
            log.warn("过程事件写入失败（不影响任务执行） jobKind={} jobRef={} level={} reason={}",
                    jobKind, jobRef, level, doorDown.getMessage());
        }
    }

    /**
     * Next window of events landed after {@code cursor}, ascending by row id.
     * Replaying the same cursor returns the same window (strict {@code id >}
     * boundary, no offset arithmetic). Fail-soft: an unreachable door answers
     * an empty window.
     */
    public List<TaskProcessEvent> fetchSince(String jobKind, String jobRef, String runToken,
                                             long cursor, int limit) {
        RowMapper<TaskProcessEvent> mapper = (rs, rowNum) -> new TaskProcessEvent(
                rs.getLong("id"),
                rs.getString("level"),
                rs.getString("summary"),
                rs.getString("detail"),
                rs.getTimestamp("created_at").toLocalDateTime());
        try {
            return jdbc.query(FIND_SQL, new MapSqlParameterSource()
                    .addValue("jobKind", jobKind)
                    .addValue("jobRef", jobRef)
                    .addValue("runToken", runToken)
                    .addValue("cursor", cursor)
                    .addValue("limit", limit), mapper);
        } catch (RuntimeException doorDown) {
            log.warn("过程事件读取门不可达，按空窗口继续 jobKind={} jobRef={} reason={}",
                    jobKind, jobRef, doorDown.getMessage());
            return List.of();
        }
    }

    /**
     * Run history of one job face: one aggregated row per run token, newest
     * run first. This is the read side behind a revisit entry (history list);
     * the per-run replay itself stays on {@link #fetchSince}. Rows landed
     * without a run token never show as a run. Fail-soft like every other
     * door of this ledger: an unreachable read answers an empty list.
     */
    public List<TaskEventRun> listRuns(String jobKind, String jobRef, int limit) {
        RowMapper<TaskEventRun> mapper = (rs, rowNum) -> new TaskEventRun(
                rs.getString("run_token"),
                rs.getLong("event_count"),
                rs.getTimestamp("first_at").toLocalDateTime(),
                rs.getTimestamp("last_at").toLocalDateTime(),
                rs.getInt("error_rows") > 0);
        try {
            return jdbc.query(
                    "SELECT run_token,"
                            + " COUNT(*) AS event_count,"
                            + " MIN(created_at) AS first_at,"
                            + " MAX(created_at) AS last_at,"
                            + " MAX(CASE WHEN level = 'error' THEN 1 ELSE 0 END) AS error_rows"
                            + " FROM tp_ai_task_event"
                            + " WHERE job_kind = :jobKind AND job_ref = :jobRef"
                            + " AND run_token IS NOT NULL AND run_token <> ''"
                            + " GROUP BY run_token"
                            + " ORDER BY MAX(id) DESC"
                            + " LIMIT :limit",
                    new MapSqlParameterSource()
                            .addValue("jobKind", jobKind)
                            .addValue("jobRef", jobRef)
                            .addValue("limit", limit), mapper);
        } catch (RuntimeException doorDown) {
            log.warn("运行汇总读取门不可达，按空清单继续 jobKind={} jobRef={} reason={}",
                    jobKind, jobRef, doorDown.getMessage());
            return List.of();
        }
    }
}
