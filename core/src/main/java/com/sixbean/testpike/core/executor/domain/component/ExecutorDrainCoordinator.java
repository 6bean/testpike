package com.sixbean.testpike.core.executor.domain.component;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.lease.domain.service.ResourceLeaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Executor pool drain coordinator (AA wave T294a package 1): the lease/drain
 * protocol decision side.
 * <p>S3 semantic chain = stop claim → running count zero → exit. The drain flag
 * (tp_executor.drain_requested) is what the claim gate reads (ExecTaskServiceImpl);
 * this component pairs every flag transition with a kind=executor ownership lease
 * in tp_resource_lease so an in-progress drain has exactly one live token.
 * The token doubles as the N4 timeout handle: {@link #sweepStaleDrains()} reclaims
 * leases whose holder vanished — a still-pending scale-in command re-anchors the
 * token (self-heal, a long task must outlive the TTL), an abandoned drain releases
 * the flag and returns the node to the pool. Business body is unconditional by
 * design (R-rule on sweep components); scheduling lives in
 * {@link ExecutorDrainSweepScheduler}.
 */
@Component
public class ExecutorDrainCoordinator {

    private static final Logger log = LoggerFactory.getLogger(ExecutorDrainCoordinator.class);

    /** Lease kind literal for drain ownership tokens (tp_resource_lease.kind). */
    public static final String LEASE_KIND = "executor";

    /** In-flight task status on tp_exec_task (column contract: 2=running). */
    private static final int TASK_STATUS_RUNNING = 2;

    private final ResourceLeaseService leaseService;
    private final NamedParameterJdbcTemplate jdbc;

    public ExecutorDrainCoordinator(ResourceLeaseService leaseService,
                                    NamedParameterJdbcTemplate jdbc) {
        this.leaseService = leaseService;
        this.jdbc = jdbc;
    }

    /**
     * Drain state snapshot for one executor.
     *
     * @param drained true = flag raised and zero tasks in flight (exit-ready)
     */
    public record DrainSnapshot(long executorId, boolean drainRequested,
                                int runningTasks, boolean drained) {
    }

    /**
     * Raise the drain flag and hold the ownership token (stop-claim side effect
     * happens through the existing claim gate). Tolerant to duplicates: while a
     * live token exists the first holder keeps it (a second scale-in enqueue for
     * the same node must not steal or double-token the drain).
     *
     * @param holderId token owner, conventionally "cmd:&lt;commandId&gt;"
     * @throws ServiceException executor row absent (plain-word 404, never a silent flag write)
     */
    @Transactional
    public void beginDrain(long executorId, String holderId) {
        Integer known = jdbc.queryForObject(
                "SELECT count(*) FROM tp_executor WHERE id = :id",
                new MapSqlParameterSource("id", executorId), Integer.class);
        if (known == null || known == 0) {
            throw new ServiceException("执行器不存在（id=" + executorId + "），无法发起排空",
                    BizErrorCode.NOT_FOUND);
        }
        String ref = String.valueOf(executorId);
        if (leaseService.activeHolder(LEASE_KIND, ref) == null) {
            try {
                leaseService.acquire(LEASE_KIND, ref, null, null, null, holderId);
            } catch (ServiceException raceLost) {
                // Another token won the concurrent begin: the drain already has one
                // owner, keeping it is the correct outcome (flag write still idempotent).
                log.debug("排空令牌并发占优让位 executor={} holder={}", ref, holderId);
            }
        }
        jdbc.update("UPDATE tp_executor SET drain_requested = true, updated_at = now()"
                + " WHERE id = :id", new MapSqlParameterSource("id", executorId));
    }

    /** Read-side view: flag + real running count from tp_exec_task (never the cached column). */
    public DrainSnapshot drainState(long executorId) {
        MapSqlParameterSource p = new MapSqlParameterSource("id", executorId);
        Boolean flag = jdbc.query(
                "SELECT drain_requested FROM tp_executor WHERE id = :id", p,
                (org.springframework.jdbc.core.ResultSetExtractor<Boolean>) rs ->
                        rs.next() ? rs.getBoolean(1) : null);
        if (flag == null) {
            throw new ServiceException("执行器不存在（id=" + executorId + "）", BizErrorCode.NOT_FOUND);
        }
        Integer running = jdbc.queryForObject(
                "SELECT count(*) FROM tp_exec_task WHERE executor_id = :id AND status = "
                        + TASK_STATUS_RUNNING, p, Integer.class);
        int runningTasks = running == null ? 0 : running;
        return new DrainSnapshot(executorId, flag, runningTasks, flag && runningTasks == 0);
    }

    /**
     * Close a drain: release the flag and hand back the ownership token
     * (release is holder-guarded and idempotent, so a foreign or missing token
     * is a no-op, never an error).
     */
    @Transactional
    public void finishDrain(long executorId, String holderId) {
        jdbc.update("UPDATE tp_executor SET drain_requested = false, updated_at = now()"
                + " WHERE id = :id", new MapSqlParameterSource("id", executorId));
        if (holderId != null) {
            leaseService.release(LEASE_KIND, String.valueOf(executorId), holderId);
        }
    }

    /**
     * N4 timeout fallback sweep: reclaim expired executor drain leases.
     * <p>Per reclaimed ref: a still-pending scale-in command (status 0/1/2, not
     * TTL-expired) re-anchors the token on that command (self-heal — a long
     * in-flight task must outlive the lease TTL); with no live command the drain
     * is abandoned, so the flag is released and the node returns to the pool.
     *
     * @return number of expired drain tokens settled this round
     */
    @Transactional
    public int sweepStaleDrains() {
        List<String> refs = leaseService.sweepExpired(LEASE_KIND);
        int settled = 0;
        for (String ref : refs) {
            long executorId;
            try {
                executorId = Long.parseLong(ref);
            } catch (NumberFormatException malformed) {
                log.warn("排空令牌 ref 非执行器 id，跳过回收 ref={}", ref);
                continue;
            }
            MapSqlParameterSource p = new MapSqlParameterSource("id", executorId);
            Long liveCmd = jdbc.query("""
                    SELECT id FROM tp_cluster_command
                    WHERE target_type = 'executor' AND target_id = :id
                      AND command_type = 'scale-in' AND status IN (0, 1, 2)
                      AND expires_at > now()
                    ORDER BY id DESC LIMIT 1
                    """, p, (org.springframework.jdbc.core.ResultSetExtractor<Long>) rs ->
                    rs.next() ? rs.getLong(1) : null);
            if (liveCmd != null) {
                try {
                    leaseService.acquire(LEASE_KIND, ref, null, null, null, "cmd:" + liveCmd);
                    log.warn("执行器排空令牌超时但 scale-in 命令 {} 仍在途，令牌已续期（N4 自愈）", liveCmd);
                } catch (ServiceException heldByOther) {
                    continue; // someone else re-anchored concurrently: leave it alone
                }
            } else {
                int rows = jdbc.update(
                        "UPDATE tp_executor SET drain_requested = false, updated_at = now()"
                                + " WHERE id = :id AND drain_requested = true", p);
                if (rows > 0) {
                    log.warn("执行器 {} 排空遗弃回收（令牌过期且无在途 scale-in 命令），节点已回池（N4 回收）",
                            executorId);
                }
            }
            settled++;
        }
        return settled;
    }
}
