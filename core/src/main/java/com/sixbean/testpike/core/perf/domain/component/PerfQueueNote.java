package com.sixbean.testpike.core.perf.domain.component;

import com.sixbean.testpike.core.executor.domain.service.ExecutorService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Queue transparency wording for perf runs ( segment: a launched run waited
 * silently — the operator saw "running" while its dispatch task had not been
 * picked up by any executor, with no way to tell pressing-in-progress from
 * queueing or how long the wait might be).
 * <p>Truth source = the shared dispatch queue itself: one perf run dispatches
 * exactly one pending task (task_type=5, envelope runId key written at the
 * single dispatch point), and the claim path consumes candidates in id order,
 * so "tasks ahead" is simply the count of still-pending perf tasks with a
 * smaller id. No parallel bookkeeping table, no second queue notion (red line:
 * W4 skeleton wiring only).
 * <p>Wording spectrum on a status=1 (in-flight) run:
 * own task pending with N ahead → queueing wording naming N;
 * own task pending with nothing ahead → queueing wording stating whether an
 * online executor exists (single-source {@link ExecutorService#hasOnlineExecutor},
 * same judgement as the claim path, never re-implemented here) — the honest
 * difference between "picked up shortly" and "nobody online to pick it up";
 * own task claimed/finished, or run already terminal → no note (null), because
 * there is nothing to wait for and a finished run must never keep showing a
 * queue line.
 * <p>The row maps come straight from the JDBC read (mutable LinkedHashMaps);
 * the note is attached under {@code queue_note} so every consumer of the run
 * view/list faces (detail header card, workbench drawer) sees it without a new
 * endpoint.
 */
@Component
public class PerfQueueNote {

    /** tp_exec_task status 1 = pending dispatch (ExecTaskServiceImpl same value). */
    private static final int TASK_PENDING = 1;
    /** tp_exec_task task_type 5 = perf run (03 protocol single dispatch channel). */
    private static final int TASK_TYPE_PERF = 5;
    /** tp_perf_run status 1 = running (never terminal): the only state a note fits. */
    private static final int RUN_STATUS_RUNNING = 1;

    private final NamedParameterJdbcTemplate jdbc;
    private final ExecutorService executorService;

    public PerfQueueNote(NamedParameterJdbcTemplate jdbc, ExecutorService executorService) {
        this.jdbc = jdbc;
        this.executorService = executorService;
    }

    /** Attach the queue note to every running row of a read window (terminal rows
     *  keep no note key — consumers read queue_note as absent/null); per-row
     *  lookups stay bounded by the page window. */
    public void annotate(List<Map<String, Object>> rows) {
        for (Map<String, Object> row : rows) {
            Object status = row.get("status");
            if (status instanceof Number n && n.intValue() == RUN_STATUS_RUNNING) {
                row.put("queue_note", noteOf(((Number) row.get("id")).longValue()));
            }
        }
    }

    /**
     * Queue wording for one run id, or null when the run has nothing to wait
     * for (its dispatch task is already claimed/gone).
     */
    public String noteOf(long runId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT t.id, (SELECT count(*) FROM tp_exec_task p"
                        + " WHERE p.task_type = :tt AND p.status = :st AND p.id < t.id) AS ahead"
                        + " FROM tp_exec_task t"
                        + " WHERE t.task_type = :tt AND t.status = :st"
                        + " AND t.action_json::jsonb->>'runId' = :r LIMIT 1",
                new MapSqlParameterSource("tt", TASK_TYPE_PERF)
                        .addValue("st", TASK_PENDING).addValue("r", String.valueOf(runId)));
        if (rows.isEmpty()) {
            return null;   // dispatch task claimed (pressing now) or absent — nothing to queue for
        }
        long ahead = ((Number) rows.get(0).get("ahead")).longValue();
        if (ahead > 0) {
            return "任务排队中：前方有 " + ahead + " 个压测任务，将按领取顺序开始压测";
        }
        if (executorService.hasOnlineExecutor()) {
            return "任务排队中：已有在线执行器，等待领取后开始压测";
        }
        return "任务排队中：当前没有可用的在线执行器，执行器节点上线后将自动开始压测";
    }
}
