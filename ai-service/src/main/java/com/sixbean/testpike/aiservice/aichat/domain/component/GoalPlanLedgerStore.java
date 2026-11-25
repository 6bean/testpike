package com.sixbean.testpike.aiservice.aichat.domain.component;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;

/**
 * Storage door of tp_ai_chat_goal_plan : one ledger
 * row per (session, plan_version). Writes go through the pure-rule layer
 * first ({@link GoalPlanLedger#normalize} demotes an anchor-less done step,
 * {@link GoalPlanLedger#sameSet} recognises a replay), so a re-published
 * identical plan is a zero-write no-op and the revision only rides a real
 * change — the same idempotency posture as the empty-save guard (R28).
 * <p>Reads are fail-soft: an unreachable door answers "no ledger" (null)
 * instead of throwing, because a ledger outage must never break a live
 * dialog turn. Writes surface their exception — the caller (reconcile door
 * in the orchestrator) owns the fail-soft choice there.
 */
@org.springframework.stereotype.Component
public final class GoalPlanLedgerStore {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(GoalPlanLedgerStore.class);

    private static final String FIND_SQL =
            "SELECT steps_json FROM tp_ai_chat_goal_plan"
                    + " WHERE session_id = :sessionId AND plan_version = :planVersion";

    private final NamedParameterJdbcTemplate jdbc;

    public GoalPlanLedgerStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Stored canonical steps of one ledger, or null (absent / door down). */
    public String findStepsJson(long sessionId, int planVersion) {
        try {
            List<String> rows = jdbc.queryForList(FIND_SQL, params(sessionId, planVersion, null),
                    String.class);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (RuntimeException doorDown) {
            // Fail-soft read (the storage rule above): "no ledger" beats a
            // thrown dialog turn; the reason is journaled, never swallowed.
            log.warn("Goal plan ledger read gate unreachable, continuing without ledger sessionId={} version={} reason={}",
                    sessionId, planVersion, doorDown.getMessage());
            return null;
        }
    }

    /**
     * Persist a (possibly re-published) step list for one ledger identity.
     *
     * @return true when the row was created or updated, false when the input
     *         is unparsable (nothing is written) or equals the stored set
     */
    public boolean upsertIfChanged(long sessionId, int planVersion, String stepsJson) {
        String canonical = GoalPlanLedger.normalize(stepsJson);
        if (canonical == null) {
            // Unparsable input never reaches the database — a corrupt step
            // list would poison the resume block of every later turn.
            return false;
        }
        String existing = findStepsJson(sessionId, planVersion);
        if (existing == null) {
            jdbc.update(INSERT_SQL, params(sessionId, planVersion, canonical));
            return true;
        }
        canonical = GoalPlanLedger.preserveUnverifiedNote(existing, canonical);
        if (GoalPlanLedger.sameSet(existing, canonical)) {
            // Idempotent replay: zero writes, revision untouched (R28).
            return false;
        }
        jdbc.update(UPDATE_SQL, params(sessionId, planVersion, canonical));
        return true;
    }

    private static final String INSERT_SQL =
            "INSERT INTO tp_ai_chat_goal_plan (session_id, plan_version, steps_json, revision)"
                    + " VALUES (:sessionId, :planVersion, :steps, 1)"
                    + " ON CONFLICT (session_id, plan_version) DO UPDATE"
                    + " SET steps_json = EXCLUDED.steps_json,"
                    + " revision = tp_ai_chat_goal_plan.revision + 1, updated_at = now()";

    private static final String UPDATE_SQL =
            "UPDATE tp_ai_chat_goal_plan SET steps_json = :steps,"
                    + " revision = revision + 1, updated_at = now()"
                    + " WHERE session_id = :sessionId AND plan_version = :planVersion";

    private static MapSqlParameterSource params(long sessionId, int planVersion, String steps) {
        MapSqlParameterSource source = new MapSqlParameterSource()
                .addValue("sessionId", sessionId)
                .addValue("planVersion", planVersion);
        if (steps != null) {
            source.addValue("steps", steps);
        }
        return source;
    }
}
