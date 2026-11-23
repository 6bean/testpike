package com.sixbean.testpike.core.support;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Test-side fleet quiesce window for the shared dev database .
 *
 * <p>Some executor-routing suites must observe a deterministic online view:
 * the persistent local runner keeps heartbeating and claims any engine-capable
 * pending task within its 2-second poll loop, so assertions like "the task
 * stays queued" race it. The old answer was {@code TRUNCATE tp_executor},
 * which deleted rows owned by concurrent suites and forced the runner to
 * re-register with a new id (FK CASCADE fallout).
 *
 * <p>This helper only ages {@code last_heartbeat} for executors that do NOT
 * match the keep pattern, for the duration of one test method. The online
 * view (isOnline/listOnline/onlineCapabilities — same expression, same hot
 * key) recomputes freshness on every call, so the nodes simply drop out while
 * the window is open and rejoin either via {@link #restore} or the runner's
 * next heartbeat (5s self-heal floor). Strictly lighter than the TRUNCATE it
 * replaces: no row deletion, no id churn, millisecond blast radius.
 *
 * <p>Boundary of the semantic: it acts on this dev database only, and is
 * test-support code — never consumed by main sources. A concurrent suite
 * whose executor is caught inside the window sees one "executor offline"
 * rejection at worst (its heartbeat refreshes immediately after restore).
 */
public final class FleetViewQuiescer {

    private FleetViewQuiescer() {
    }

    /**
     * Drop every online executor except those whose name matches the keep
     * pattern out of the online view by aging their heartbeat.
     *
     * @return ids of the aged executors (feed them to {@link #restore})
     */
    public static List<Long> quiesceOthers(JdbcTemplate jdbc, String keepNamePattern) {
        return jdbc.queryForList(
                "UPDATE tp_executor SET last_heartbeat = now() - interval '2 hours'"
                        + " WHERE status = 1 AND name NOT LIKE ? RETURNING id",
                Long.class, keepNamePattern);
    }

    /**
     * Bring the executors captured by {@link #quiesceOthers} back into the
     * online view by refreshing their heartbeat. Idempotent: an empty id list
     * is a no-op, so it is safe to call from finally blocks unconditionally.
     */
    public static void restore(JdbcTemplate jdbc, List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        jdbc.update("UPDATE tp_executor SET last_heartbeat = now() WHERE id IN ("
                + ids.stream().map(id -> "?").reduce((a, b) -> a + "," + b).orElse("") + ")",
                ids.toArray());
    }
}
