package com.sixbean.testpike.core.apitest.domain.component;

import com.sixbean.testpike.core.apitest.api.dto.ApiRunSetInfo;
import com.sixbean.testpike.core.apitest.domain.component.ApiRunSetPlanSupport.PinnedStep;
import com.sixbean.testpike.core.apitest.domain.component.ApiRunSetPlanSupport.RunSetPlan;
import com.sixbean.testpike.core.apitest.domain.component.ApiRunSetPlanSupport.StepOutcome;
import com.sixbean.testpike.core.executor.domain.service.ExecTaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.sixbean.testpike.core.apitest.infrastructure.dataobject.ApiRunChildRow;

import java.util.List;

/**
 * Collection-run orchestrator ; server-side sequential dispatch —
 * design route (a), executor unchanged).
 * <p>One hop in flight per set: when a child run reaches a terminal status, this
 * component decides — via the shared static plan (ApiRunSetPlanSupport, no second
 * grammar) — whether the next pending hop may be dispatched, whether remaining
 * hops are suppressed by the interruption mode, or whether the set row closes
 * with honest passed/failed/skipped aggregates.
 * <p>Double-dispatch protection: the set row is locked FOR UPDATE inside the
 * advance transaction and every dispatch stamps dispatched_at — a second,
 * concurrent or replayed callback finds the stamp and never re-sends (a
 * duplicate dispatch would hit the system under test twice).
 * <p>Dispatch failures are logged, never thrown: the result chain that called us
 * has already committed the child terminal; the ledger stays honestly pending
 * and the operator can re-drive from the detail page later.
 */
@Component
public class ApiRunSetAdvancer {

    /** Child status written for hops suppressed by the interruption mode. */
    public static final int CHILD_SKIPPED = 4;
    /** Set statuses: 0=queued 1=running 2=done-passed 3=done-with-failures 4=interrupted. */
    public static final int SET_RUNNING = 1;
    public static final int SET_DONE_PASSED = 2;
    public static final int SET_DONE_FAILED = 3;
    public static final int SET_INTERRUPTED = 4;

    private static final Logger log = LoggerFactory.getLogger(ApiRunSetAdvancer.class);

    private final NamedParameterJdbcTemplate namedJdbc;
    private final ApiRunSetPlanSupport planSupport;
    private final ApiTestEnvelopeAssembler envelopeAssembler;
    private final ExecTaskService execTaskService;
    private final TransactionTemplate newTx;

    public ApiRunSetAdvancer(NamedParameterJdbcTemplate namedJdbc,
                             ApiRunSetPlanSupport planSupport,
                             ApiTestEnvelopeAssembler envelopeAssembler,
                             @Lazy ExecTaskService execTaskService,
                             PlatformTransactionManager txManager) {
        this.namedJdbc = namedJdbc;
        this.planSupport = planSupport;
        this.envelopeAssembler = envelopeAssembler;
        this.execTaskService = execTaskService;
        this.newTx = new TransactionTemplate(txManager);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Entry from ApiRunResultFinalizer after a child run of a set reached its
     * terminal status (standalone runs — run_set_id NULL — are a no-op).
     * Inside the result transaction the advance is deferred to after-commit:
     * the fresh REQUIRES_NEW pass must observe the committed child terminal,
     * otherwise the chain would silently stall on a stale status read.
     */
    public void onChildFinished(long runId) {
        List<Long> setIds = namedJdbc.query("""
                SELECT run_set_id FROM tp_apitest_run
                 WHERE id = :runId AND run_set_id IS NOT NULL
                """, new MapSqlParameterSource("runId", runId), (rs, n) -> rs.getLong(1));
        setIds.forEach(this::schedule);
    }

    /**
     * Entry from the ledger create: dispatches the first hop once the ledger
     * transaction commits (the advance cannot see its own uncommitted rows).
     */
    public void dispatchAfterCommit(long runSetId) {
        schedule(runSetId);
    }

    private void schedule(long runSetId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        advance(runSetId);
                    } catch (Exception e) {
                        log.error("集合运行 {} 提交后推进失败——账本保持诚实状态，可在详情页重新推进", runSetId, e);
                    }
                }
            });
            return;
        }
        advance(runSetId);
    }

    /**
     * One advance pass inside its own transaction under the set-row lock:
     * dispatch the next permitted hop (single in flight), suppress what the
     * mode forbids, close the ledger when every child is terminal.
     */
    public void advance(long runSetId) {
        newTx.executeWithoutResult(ignored -> doAdvance(runSetId));
    }

    private void doAdvance(long runSetId) {
        LockedSet locked = lockSet(runSetId);
        if (locked == null) {
            log.warn("集合运行 {} 不存在或已清理——推进跳过", runSetId);
            return;
        }
        if (locked.status() == SET_DONE_PASSED || locked.status() == SET_DONE_FAILED
                || locked.status() == SET_INTERRUPTED) {
            return; // closed ledger: replayed callbacks must not resurrect hops
        }
        if (locked.envId() == null) {
            return; // host whitelist not chosen yet: honest pending, no dispatch (single-run posture)
        }
        RunSetPlan plan = planSupport.buildPlan(locked.pinnedJson());
        List<ApiRunChildRow> children = queryChildren(runSetId);

        // Walk the queue in tree order: one dispatch per pass, suppressed rows
        // drop to the skipped terminal immediately (no envelope, no executor task).
        for (ApiRunChildRow child : children) {
            if (child.getStatus() != 0) {
                continue;
            }
            if (child.getDispatchedAt() != null) {
                return; // a hop is in flight — sequential model, wait for its result
            }
            List<Long> dispatchable = plan.nextDispatchable(locked.interruptionMode(),
                    terminalOutcomes(children));
            if (!dispatchable.contains(child.getRequestId())) {
                namedJdbc.update("""
                        UPDATE tp_apitest_run
                           SET status = 4, error_msg = :reason
                         WHERE id = :id AND status = 0 AND dispatched_at IS NULL
                        """, new MapSqlParameterSource()
                        .addValue("reason", "被中断模式抑制跳过（依赖链上存在失败跳）")
                        .addValue("id", child.getId()));
                continue;
            }
            dispatchNextHop(locked, child);
            return; // strictly one hop in flight
        }
        close(locked, queryChildren(runSetId));
    }

    /** Envelope + task for the chosen hop; failure keeps the row pending and honest. */
    private void dispatchNextHop(LockedSet locked, ApiRunChildRow child) {
        try {
            ApiTestEnvelopeAssembler.DispatchPayload payload = envelopeAssembler.assemble(child.getId());
            execTaskService.createApiTestRun(payload.projectId(), payload.envelopeJson());
        } catch (Exception e) {
            log.error("集合运行 {} 跳 {} 派发失败——该行保持待执行，不重复发送（后续回传可再驱动）",
                    locked.id(), child.getId(), e);
            return;
        }
        namedJdbc.update("""
                UPDATE tp_apitest_run
                   SET dispatched_at = now()
                 WHERE id = :id AND dispatched_at IS NULL
                """, new MapSqlParameterSource("id", child.getId()));
        if (locked.status() == 0) {
            namedJdbc.update("UPDATE tp_apitest_run_set SET status = 1, updated_at = now() WHERE id = :id",
                    new MapSqlParameterSource("id", locked.id()));
        }
    }

    /** Terminal close: aggregate the traffic lights and freeze the set status. */
    private void close(LockedSet locked, List<ApiRunChildRow> children) {
        int passed = 0;
        int failed = 0;
        int skipped = 0;
        for (ApiRunChildRow c : children) {
            switch (c.getStatus()) {
                case 1 -> passed++;
                case 2, 3 -> failed++;
                case CHILD_SKIPPED -> skipped++;
                default -> {
                    // unreachable: close runs only when every child is terminal
                }
            }
        }
        int status = skipped > 0 ? SET_INTERRUPTED
                : failed > 0 ? SET_DONE_FAILED
                : passed == locked.totalSteps() ? SET_DONE_PASSED : SET_DONE_FAILED;
        namedJdbc.update("""
                UPDATE tp_apitest_run_set
                   SET status = :status, passed_count = :passed, failed_count = :failed,
                       skipped_count = :skipped, updated_at = now()
                 WHERE id = :id
                """, new MapSqlParameterSource()
                .addValue("status", status)
                .addValue("passed", passed)
                .addValue("failed", failed)
                .addValue("skipped", skipped)
                .addValue("id", locked.id()));
    }

    private List<StepOutcome> terminalOutcomes(List<ApiRunChildRow> children) {
        return children.stream()
                .filter(c -> c.getStatus() == 1 || c.getStatus() == 2 || c.getStatus() == 3)
                .map(c -> new StepOutcome(c.getRequestId(), c.getStatus()))
                .toList();
    }

    /** Row-level lock on the set (docs/10 concurrency posture): blocks the double callback. */
    private LockedSet lockSet(long runSetId) {
        List<LockedSet> rows = namedJdbc.query("""
                SELECT s.id, s.interruption_mode AS interruptionMode, s.status,
                       s.pinned_json::text AS pinnedJson, s.total_steps AS totalSteps,
                       s.env_id AS envId
                  FROM tp_apitest_run_set s
                 WHERE s.id = :id
                   FOR UPDATE
                """, new MapSqlParameterSource("id", runSetId),
                (rs, n) -> new LockedSet(rs.getLong("id"), rs.getString("interruptionMode"),
                        rs.getInt("status"), rs.getString("pinnedJson"), rs.getInt("totalSteps"),
                        rs.getObject("envId") == null ? null : rs.getLong("envId")));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private List<ApiRunChildRow> queryChildren(long runSetId) {
        return namedJdbc.query("""
                SELECT r.id, r.request_id AS requestId, r.status, r.dispatched_at AS dispatchedAt
                  FROM tp_apitest_run r
                 WHERE r.run_set_id = :setId
                 ORDER BY r.seq_no
                """, new MapSqlParameterSource("setId", runSetId),
                new BeanPropertyRowMapper<>(ApiRunChildRow.class));
    }

    /** The locked set header. */
    private record LockedSet(long id, String interruptionMode, int status,
                             String pinnedJson, int totalSteps, Long envId) {
    }
}
