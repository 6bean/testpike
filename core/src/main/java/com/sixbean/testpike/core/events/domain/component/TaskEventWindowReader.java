package com.sixbean.testpike.core.events.domain.component;

import com.sixbean.testpike.core.config.domain.service.ConfigService;
import com.sixbean.testpike.core.events.api.dto.TaskEventRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Single-source cursor window of the process event ledger (tp_ai_task_event).
 * Every registered job kind slices the same ledger through this one door —
 * the extraction tail (doc-extract, ) and the script-generation tail
 * (steps-gen, S4 segment B) — so the SQL, the row shape and the window
 * clamp live exactly once here instead of being re-written per domain.
 * <p>Fail-soft by design, mirroring the ledger's writer door: an unreachable
 * read logs and answers an empty window, so an observation face can never
 * fail the call that only wanted to show progress. A blank job kind, blank
 * business-object anchor or blank run token is a "nothing to replay" answer,
 * not an error.
 */
@Component
public class TaskEventWindowReader {

    private static final Logger log = LoggerFactory.getLogger(TaskEventWindowReader.class);

    /** Lower bound of a readable window — smaller requests are raised, so a
     *  tail viewer never starves on a toy page. */
    static final int WINDOW_MIN = 10;
    /** Upper bound of a readable window — no unbounded page against the
     *  append-only ledger. */
    static final int WINDOW_MAX = 1000;
    /** Window used when the client sends no limit (factory default of the
     *  hot key ai.stream.chunk-size). */
    static final int WINDOW_DEFAULT = 200;

    private final JdbcTemplate jdbcTemplate;
    private final ConfigService configService;

    public TaskEventWindowReader(JdbcTemplate jdbcTemplate, ConfigService configService) {
        this.jdbcTemplate = jdbcTemplate;
        this.configService = configService;
    }

    /**
     * Rows of one job run landed after {@code cursor}, ascending by row id,
     * at most {@code windowSize(limit)} entries. Replaying the same cursor
     * returns exactly the same window (strict {@code id >} boundary, no
     * offset arithmetic).
     */
    public List<TaskEventRow> since(String jobKind, String jobRef, String runToken,
                                    long cursor, Integer limit) {
        if (jobKind == null || jobKind.isBlank()
                || jobRef == null || jobRef.isBlank()
                || runToken == null || runToken.isBlank()) {
            return List.of();
        }
        int window = windowSize(limit);
        try {
            return jdbcTemplate.query("SELECT id, level, summary, detail,"
                            + " to_char(created_at, 'YYYY-MM-DD\"T\"HH24:MI:SS') AS landed_at"
                            + " FROM tp_ai_task_event"
                            + " WHERE job_kind=? AND job_ref=? AND run_token=? AND id>?"
                            + " ORDER BY id ASC LIMIT ?",
                    (rs, i) -> new TaskEventRow(rs.getLong(1), rs.getString(2),
                            rs.getString(3), rs.getString(4), rs.getString(5)),
                    jobKind, jobRef, runToken, cursor, window);
        } catch (RuntimeException doorDown) {
            log.warn("过程事件台账读取不可达，按空窗口继续: jobKind={} jobRef={} reason={}",
                    jobKind, jobRef, doorDown.getMessage());
            return List.of();
        }
    }

    /**
     * Window of the newest run for one business anchor: the run token is
     * resolved as the token that landed the highest row id for
     * (jobKind, jobRef), then the ordinary cursor window is served for that
     * token. This is the observation door for job kinds whose run token
     * lives only inside the ledger (no task-state row to read it from —
     * e.g. the channel-B spec extraction): a retried attempt automatically
     * becomes the replayed window and stale attempts never mix into it.
     * Same fail-soft contract as {@link #since}: blank anchors or an
     * unreachable door honestly answer an empty window, never an error.
     */
    public List<TaskEventRow> sinceLatestRun(String jobKind, String jobRef,
                                             long cursor, Integer limit) {
        if (jobKind == null || jobKind.isBlank()
                || jobRef == null || jobRef.isBlank()) {
            return List.of();
        }
        try {
            List<String> latest = jdbcTemplate.queryForList(
                    "SELECT run_token FROM tp_ai_task_event"
                            + " WHERE job_kind=? AND job_ref=? ORDER BY id DESC LIMIT 1",
                    String.class, jobKind, jobRef);
            if (latest.isEmpty()) {
                return List.of();
            }
            return since(jobKind, jobRef, latest.get(0), cursor, limit);
        } catch (RuntimeException doorDown) {
            log.warn("过程事件最新运行锚解析不可达，按空窗口继续: jobKind={} jobRef={} reason={}",
                    jobKind, jobRef, doorDown.getMessage());
            return List.of();
        }
    }

    /**
     * Run history of one job face: aggregated rows per run token, newest run
     * first. This is the single revisit door shared by the login-state probe
     * trail and the credential test trail — one implementation for both
     * consumers, no per-domain copy. Rows landed without a run token never
     * show as a run; the window-size clamp of {@link #windowSize} bounds the
     * page. Same fail-soft contract as the cursor window: blank anchors or an
     * unreachable read honestly answer an empty list, never an error.
     */
    public List<com.sixbean.testpike.core.events.api.dto.TaskEventRunRow> runs(
            String jobKind, String jobRef, Integer limit) {
        if (jobKind == null || jobKind.isBlank()
                || jobRef == null || jobRef.isBlank()) {
            return List.of();
        }
        int window = windowSize(limit);
        try {
            return jdbcTemplate.query("SELECT run_token,"
                            + " COUNT(*) AS event_count,"
                            + " MAX(CASE WHEN level = 'error' THEN 1 ELSE 0 END) AS error_rows,"
                            + " to_char(MIN(created_at), 'YYYY-MM-DD\"T\"HH24:MI:SS') AS first_at,"
                            + " to_char(MAX(created_at), 'YYYY-MM-DD\"T\"HH24:MI:SS') AS last_at"
                            + " FROM tp_ai_task_event"
                            + " WHERE job_kind=? AND job_ref=?"
                            + " AND run_token IS NOT NULL AND run_token <> ''"
                            + " GROUP BY run_token"
                            + " ORDER BY MAX(id) DESC"
                            + " LIMIT ?",
                    (rs, i) -> new com.sixbean.testpike.core.events.api.dto.TaskEventRunRow(
                            rs.getString(1), rs.getLong(2), rs.getInt(3) > 0,
                            rs.getString(4), rs.getString(5)),
                    jobKind, jobRef, window);
        } catch (RuntimeException doorDown) {
            log.warn("run-history read unreachable, honest empty list: jobKind={} jobRef={} reason={}",
                    jobKind, jobRef, doorDown.getMessage());
            return List.of();
        }
    }

    /**
     * Window size for the event tail: an explicit positive client limit wins,
     * otherwise the hot key ai.stream.chunk-size (shipped default 200); a
     * garbage value degrades to the default instead of failing the read. Both
     * faces are clamped into 10~1000 — no unbounded page, no toy page.
     */
    public int windowSize(Integer limit) {
        int requested;
        if (limit != null && limit > 0) {
            requested = limit;
        } else {
            String raw = configService.getValue("ai.stream.chunk-size", "200");
            try {
                requested = Integer.parseInt(raw == null ? "" : raw.trim());
            } catch (NumberFormatException bad) {
                log.warn("过程事件窗口配置不可解析，按出厂缺省 200: raw={}", raw);
                requested = WINDOW_DEFAULT;
            }
        }
        return Math.min(Math.max(requested, WINDOW_MIN), WINDOW_MAX);
    }
}
