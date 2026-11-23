package com.sixbean.testpike.core.webelement.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.explore.api.dto.ExploreSessionView;
import com.sixbean.testpike.common.webelement.SiteSurveyRunView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Whole-site survey run ledger relay .
 *
 * <p>The executor's deep-crawl loop emits exactly one journal frame per page
 * through the shared explore step door; this component turns those frames into
 * honest run-ledger facts on {@code tp_site_survey_run} (located by the
 * owning explore session id — a frame for any other session is a no-op, so
 * single-page surveys and plain explorations never touch this ledger):
 * <ul>
 * <li>page counters ride the cumulative snapshot the loop carries in
 * {@code stepAction.arguments.counters} (pages_total/pages_ok/
 * pages_failed/links_seen/out_of_scope/excluded/sibling_counts/
 * naming_degraded) — later frames win, malformed frames are skipped
 * with a WARN (a tally outage must never fail the step it observes,
 * same posture as the survey tail frames);</li>
 * <li>{@code ledger_upserted} accumulates the collector write outcomes
 * core-side (only actions that actually wrote a row count);</li>
 * <li>a non-ok page appends one named entry to {@code failure_notes}
 * (url + status + reason) so a failed page is always pointable.</li>
 * </ul>
 *
 * <p>{@link #finishBySession} maps the session's terminal status onto the run
 * with the honest ratio rule (adjudication D1): failed pages at least half of
 * the pages attempted = failed; any failed page = partial; a clean session
 * that ended by budget/partial policy = partial; otherwise completed. Cancel
 * maps to cancelled. The rule reads the ledger counters as last reported by
 * the relay — the loop's final frame and the session terminal state agree by
 * construction because both ride the single step door.
 *
 * <p>The table keeps {@code started_at}/{@code finished_at} only (no
 * per-update timestamp column by design: the ledger facts ARE the row, so a
 * frame write never needs a modification stamp).
 */
@Component
public class SiteSurveyRunLedger {

    private static final Logger log = LoggerFactory.getLogger(SiteSurveyRunLedger.class);

    /** stepAction name of one page journal frame (executor wire contract;
     *  single-sourced in ExploreEnvelopeContract — the ai-service kernel
     *  replay is the second consumer). */
    public static final String SITE_PAGE_FRAME_ACTION =
            com.sixbean.testpike.common.util.ExploreEnvelopeContract.SITE_PAGE_FRAME_ACTION;

    /** Session terminal codes mirrored from the explore door (1..5). */
    public static final int STATUS_DONE = 2;
    public static final int STATUS_BUDGET_STOP = 3;
    public static final int STATUS_PARTIAL = 4;
    public static final int STATUS_CANCELLED = 5;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final WebElementPageKeys pageKeys;

    public SiteSurveyRunLedger(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper,
                               WebElementPageKeys pageKeys) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.pageKeys = pageKeys;
    }

    /**
     * Apply one step write-back of a site session to its run ledger. Never
     * throws: any parse or write failure degrades to a WARN — the step fact
     * itself is already journaled by the caller.
     *
     * @param session      the owning explore session view (goal gate applied
     *                     by the caller via {@link WebElementSurveyCollector#isSiteSurveyGoal})
     * @param stepAction   raw stepAction JSON of the write-back
     * @param ledgerWrites collector outcomes that actually wrote a row this step
     */
    public void notePageFrame(ExploreSessionView session, String stepAction, int ledgerWrites) {
        try {
            Long runId = findRunId(session.getId());
            if (runId == null) {
                return; // dispatch-less session: nothing to tally, stay silent
            }
            JsonNode args = pageArguments(stepAction);
            if (args == null) {
                return;
            }
            if (ledgerWrites > 0) {
                jdbc.update("UPDATE tp_site_survey_run SET ledger_upserted ="
                                + " ledger_upserted + :w WHERE id = :id",
                        new MapSqlParameterSource("w", ledgerWrites).addValue("id", runId));
            }
            JsonNode counters = args.path("counters");
            if (counters.isObject()) {
                applyCounters(runId, counters);
            }
            String status = args.path("status").asText("ok");
            if (!"ok".equals(status)) {
                appendFailureNote(runId, args.path("url").asText(""), status,
                        args.path("reason").asText(""));
            } else if (ledgerWrites > 0) {
                tallyPageWrites(runId, args.path("url").asText(""), ledgerWrites);
            }
        } catch (Exception bookkeepingFailure) {
            log.warn("site survey run ledger write-back skipped (step fact unaffected)"
                    + " sessionId={}: {}", session.getId(), bookkeepingFailure.getMessage());
        }
    }

    /**
     * Map the session terminal status onto the run row. Cancel path passes
     * {@link #STATUS_CANCELLED}; budget/partial endings pass
     * {@link #STATUS_BUDGET_STOP} or {@link #STATUS_PARTIAL}; a normal end
     * passes {@link #STATUS_DONE}. Sessions without a run row are silent no-ops.
     */
    public void finishBySession(long sessionId, int sessionStatus) {
        try {
            Long runId = findRunId(sessionId);
            if (runId == null) {
                return;
            }
            String runStatus;
            if (sessionStatus == STATUS_CANCELLED) {
                runStatus = "cancelled";
            } else {
                List<Map<String, Object>> rows = jdbc.queryForList(
                        "SELECT pages_total, pages_failed FROM tp_site_survey_run WHERE id = :id",
                        new MapSqlParameterSource("id", runId));
                int total = rows.isEmpty() ? 0 : ((Number) rows.get(0).get("pages_total")).intValue();
                int failed = rows.isEmpty() ? 0
                        : ((Number) rows.get(0).get("pages_failed")).intValue();
                if (total > 0 && failed * 2 >= total) {
                    runStatus = "failed";            // honest ratio: never claim done
                } else if (failed > 0
                        || sessionStatus == STATUS_BUDGET_STOP
                        || sessionStatus == STATUS_PARTIAL) {
                    runStatus = "partial";
                } else {
                    runStatus = sessionStatus == STATUS_DONE ? "completed" : "partial";
                }
            }
            jdbc.update("UPDATE tp_site_survey_run SET status = :s,"
                            + " finished_at = now() WHERE id = :id AND status = 'running'",
                    new MapSqlParameterSource("s", runStatus).addValue("id", runId));
        } catch (Exception bookkeepingFailure) {
            log.warn("site survey terminal write-back skipped sessionId={}: {}",
                    sessionId, bookkeepingFailure.getMessage());
        }
    }

    /**
     * Naming-supply outage tally (whole-site ledger relay face): the relay
     * counted one page whose candidates could not be named because the naming
     * door was unreachable. The column counts pages, never candidates, and
     * only this door increments it — the executor journal frames carry no
     * naming_degraded key, so the cumulative snapshot can neither double nor
     * zero the established count. Never throws: like every other ledger face
     * the tally is bookkeeping and may not fail the step it observes.
     */
    public void noteNamingDegraded(long sessionId) {
        try {
            Long runId = findRunId(sessionId);
            if (runId == null) {
                return;
            }
            jdbc.update("UPDATE tp_site_survey_run SET naming_degraded ="
                            + " naming_degraded + 1 WHERE id = :id",
                    new MapSqlParameterSource("id", runId));
        } catch (Exception bookkeepingFailure) {
            log.warn("site survey naming-degraded tally skipped sessionId={}: {}",
                    sessionId, bookkeepingFailure.getMessage());
        }
    }

    /**
 * Dispatch-time frozen rules snapshot of the session's latest run
 * . Returns the raw JSON text, or null when the
 * session carries no run row — the caller answers honestly instead of
 * letting the kernel dispatch a blind crawl.
 */
    public String rulesSnapshotText(long sessionId) {
        Long runId = findRunId(sessionId);
        if (runId == null) {
            return null;
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT rules_snapshot::text AS snapshot FROM tp_site_survey_run WHERE id = :id",
                new MapSqlParameterSource("id", runId));
        if (rows.isEmpty() || rows.get(0).get("snapshot") == null) {
            return null;
        }
        return String.valueOf(rows.get(0).get("snapshot"));
    }

    // ---------------------------------------------------------------- parts

    /**
     * Fold the frame URL to its page key (single source WebElementPageKeys,
     * never a second fold rule here) and accumulate this step's ledger writes
     * under that key. Degrades to a WARN on unparseable URLs: the per-page
     * tally feeds the review proposal, it must never fail the step observed.
     */
    private void tallyPageWrites(long runId, String url, int writes) {
        try {
            String pageKey = pageKeys.pageKey(url);
            jdbc.update("UPDATE tp_site_survey_run SET page_writes ="
                            + " page_writes || jsonb_build_object(:k,"
                            + " coalesce((page_writes ->> :k)::int, 0) + :w)"
                            + " WHERE id = :id",
                    new MapSqlParameterSource("k", pageKey)
                            .addValue("w", writes).addValue("id", runId));
        } catch (Exception malformed) {
            log.warn("site survey per-page tally skipped for run {} (url {}): {}",
                    runId, url, malformed.getMessage());
        }
    }

    /** Owning profile of a run row (null when the run does not exist). */
    public Long profileOfRun(long runId) {
        List<Long> ids = jdbc.queryForList(
                "SELECT profile_id FROM tp_site_survey_run WHERE id = :id",
                new MapSqlParameterSource("id", runId), Long.class);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /**
     * Server-side ownership re-read: the run's project must equal the
     * caller's claim (never trust a client-claimed project on reads that
     * cross the profile boundary).
     */
    public void assertRunWithinProject(long runId, long projectId) {
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM tp_site_survey_run WHERE id = :id AND project_id = :p",
                new MapSqlParameterSource("id", runId).addValue("p", projectId), Long.class);
        if (ids.isEmpty()) {
            throw new com.sixbean.testpike.common.exception.ServiceException(
                    "该测绘记录不属于当前项目，请切换顶栏项目后从「站点档案」"
                            + "抽屉的测绘记录清单重新选择。",
                    com.sixbean.testpike.common.exception.BizErrorCode.FORBIDDEN);
        }
    }

    private Long findRunId(long sessionId) {
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM tp_site_survey_run WHERE explore_session_id = :s"
                        + " ORDER BY id DESC LIMIT 1",
                new MapSqlParameterSource("s", sessionId), Long.class);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /** arguments of a site page frame; null when the step is not one.
     *  Package-visible for the sibling risk ledger (second consumer, shared
     *  frame decoding single source). */
    JsonNode pageArguments(String stepAction) {
        if (stepAction == null || stepAction.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(stepAction);
            if (!SITE_PAGE_FRAME_ACTION.equals(root.path("name").asText())) {
                return null;
            }
            JsonNode args = root.path("arguments");
            return args.isObject() ? args : null;
        } catch (Exception malformed) {
            log.warn("site survey page frame unparseable (ledger tally skipped): {}",
                    malformed.getMessage());
            return null;
        }
    }

    private void applyCounters(long runId, JsonNode counters) {
        MapSqlParameterSource params = new MapSqlParameterSource("id", runId)
                .addValue("pagesTotal", counters.path("pages_total").asInt(-1))
                .addValue("pagesOk", counters.path("pages_ok").asInt(-1))
                .addValue("pagesFailed", counters.path("pages_failed").asInt(-1))
                .addValue("linksSeen", counters.path("links_seen").asInt(-1))
                .addValue("outOfScope", counters.path("out_of_scope").asInt(-1))
                .addValue("excluded", counters.path("excluded").asInt(-1))
                .addValue("namingDegraded", counters.path("naming_degraded").asInt(-1))
                .addValue("sibling", counters.path("sibling_counts").isObject()
                        ? counters.path("sibling_counts").toString() : "{}");
        // Cumulative snapshot: each column wins only when the frame carries a
        // fresh non-negative value (missing keys never zero an established tally).
        jdbc.update("UPDATE tp_site_survey_run SET"
                        + " pages_total     = CASE WHEN :pagesTotal >= 0 THEN :pagesTotal ELSE pages_total END,"
                        + " pages_ok        = CASE WHEN :pagesOk >= 0 THEN :pagesOk ELSE pages_ok END,"
                        + " pages_failed    = CASE WHEN :pagesFailed >= 0 THEN :pagesFailed ELSE pages_failed END,"
                        + " links_seen      = CASE WHEN :linksSeen >= 0 THEN :linksSeen ELSE links_seen END,"
                        + " out_of_scope    = CASE WHEN :outOfScope >= 0 THEN :outOfScope ELSE out_of_scope END,"
                        + " excluded        = CASE WHEN :excluded >= 0 THEN :excluded ELSE excluded END,"
                        + " naming_degraded = CASE WHEN :namingDegraded >= 0 THEN :namingDegraded ELSE naming_degraded END,"
                        + " sibling_counts  = CAST(:sibling AS JSONB)"
                        + " WHERE id = :id",
                params);
    }

    private void appendFailureNote(long runId, String url, String status, String reason) {
        List<String> notes = currentNotes(runId);
        // One note per (url,status): a replayed frame of the same page stays quiet.
        boolean alreadyNoted = notes.stream().anyMatch(n -> n.contains("\"" + url + "\"")
                && n.contains("\"" + status + "\""));
        if (alreadyNoted) {
            return;
        }
        List<String> merged = new ArrayList<>(notes);
        merged.add("{\"url\":\"" + escape(url) + "\",\"status\":\"" + status
                + "\",\"reason\":\"" + escape(reason) + "\"}");
        jdbc.update("UPDATE tp_site_survey_run SET failure_notes = CAST(:n AS JSONB)"
                        + " WHERE id = :id",
                new MapSqlParameterSource("id", runId).addValue("n", "[" + String.join(",", merged) + "]"));
    }

    private List<String> currentNotes(long runId) {
        String raw = jdbc.getJdbcTemplate().queryForObject(
                "SELECT failure_notes::text FROM tp_site_survey_run WHERE id = ?",
                String.class, runId);
        List<String> items = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(raw == null || raw.isBlank() ? "[]" : raw);
            root.forEach(node -> items.add(node.toString()));
        } catch (Exception malformed) {
            log.warn("site survey failure notes unreadable (rebuilt empty) runId={}: {}",
                    runId, malformed.getMessage());
        }
        return items;
    }

    /**
 * Bounded project segment of the run ledger, newest activity first, with the
 * profile name and start URL joined server-side .
 * The cap is clamped by the caller door; a malformed notes payload degrades
 * to an empty list with a warning, never a thrown read.
 */
    public List<SiteSurveyRunView> listRecent(long projectId, int limit) {
        return jdbc.query("""
                SELECT r.id, r.profile_id, p.name AS profile_name, p.start_url,
                       r.status, r.pages_total, r.pages_ok, r.pages_failed,
                       r.ledger_upserted, r.naming_degraded, r.explore_session_id,
                       r.failure_notes::text AS failure_notes, r.started_at, r.finished_at
                FROM tp_site_survey_run r
                JOIN tp_site_profile p ON p.id = r.profile_id
                WHERE r.project_id = :projectId
                ORDER BY r.started_at DESC, r.id DESC
                LIMIT :limit""",
                new MapSqlParameterSource().addValue("projectId", projectId).addValue("limit", limit),
                (rs, rowNum) -> new SiteSurveyRunView(
                        rs.getLong("id"),
                        rs.getLong("profile_id"),
                        rs.getString("profile_name"),
                        rs.getString("start_url"),
                        rs.getString("status"),
                        rs.getInt("pages_total"),
                        rs.getInt("pages_ok"),
                        rs.getInt("pages_failed"),
                        rs.getInt("ledger_upserted"),
                        rs.getInt("naming_degraded"),
                        (Long) rs.getObject("explore_session_id"),
                        parseNotes(rs.getString("failure_notes")),
                        rs.getTimestamp("started_at").toLocalDateTime(),
                        rs.getTimestamp("finished_at") == null
                                ? null : rs.getTimestamp("finished_at").toLocalDateTime()));
    }

    /** Explore session bound to one run (the dispatch door reads it back). */
    public Long sessionOfRun(long runId) {
        List<Long> ids = jdbc.queryForList(
                "SELECT explore_session_id FROM tp_site_survey_run WHERE id = :id",
                new MapSqlParameterSource("id", runId), Long.class);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private List<String> parseNotes(String raw) {
        List<String> items = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(raw == null || raw.isBlank() ? "[]" : raw);
            root.forEach(node -> items.add(node.toString()));
        } catch (Exception malformed) {
            log.warn("site survey failure notes unreadable in list read (rebuilt empty): {}",
                    malformed.getMessage());
        }
        return items;
    }

    private static String escape(String raw) {
        return raw == null ? "" : raw.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
