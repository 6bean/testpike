package com.sixbean.testpike.core.sched.domain.service.impl;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.common.domain.component.SortColumnPolicy;
import com.sixbean.testpike.core.batch.api.dto.BatchFromTemplateInput;
import com.sixbean.testpike.core.batch.api.dto.BatchInfo;
import com.sixbean.testpike.core.batch.domain.service.BatchService;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.sched.api.dto.ScheduleFireInfo;
import com.sixbean.testpike.core.sched.api.dto.ScheduleFireQuery;
import com.sixbean.testpike.core.sched.domain.component.ScheduleCronTimes;
import com.sixbean.testpike.core.perf.domain.service.PerfRunService;
import com.sixbean.testpike.core.sched.domain.service.PerfFireCandidate;
import com.sixbean.testpike.core.sched.domain.service.ScheduleFireService;
import com.sixbean.testpike.core.sched.domain.service.SuiteFireCandidate;
import com.sixbean.testpike.core.sched.domain.service.TemplateFireCandidate;
import com.sixbean.testpike.core.suites.api.dto.SuiteRunResult;
import com.sixbean.testpike.core.suites.domain.service.RegressionSuiteService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;


@Service
public class ScheduleFireServiceImpl implements ScheduleFireService {

    private static final Logger log = LoggerFactory.getLogger(ScheduleFireServiceImpl.class);

    
    private static final Duration CATCHUP_MARK = Duration.ofSeconds(90);

    private final JdbcTemplate jdbcTemplate;
    private final RegressionSuiteService suiteService;
    private final BatchService batchService;
    private final PerfRunService perfRunService;
    private final int catchupMinutes;

    public ScheduleFireServiceImpl(JdbcTemplate jdbcTemplate,
                                   RegressionSuiteService suiteService,
                                   BatchService batchService,
                                   PerfRunService perfRunService,
                                   @Value("${testpike.schedule.catchup-minutes:120}") int catchupMinutes) {
        this.jdbcTemplate = jdbcTemplate;
        this.suiteService = suiteService;
        this.batchService = batchService;
        this.perfRunService = perfRunService;
        this.catchupMinutes = catchupMinutes;
    }

    @Override
    public int scanAndFire(Instant now) {
        Duration window = Duration.ofMinutes(catchupMinutes);
        int fired = 0;
        fired += fireSuites(now, window);
        fired += fireTemplates(now, window);
        fired += firePerfScenarios(now, window);
        return fired;
    }

    
    private int fireSuites(Instant now, Duration window) {
        List<SuiteFireCandidate> candidates = jdbcTemplate.query("""
                SELECT id, project_id, created_by, cron_expr, last_run_at
                FROM tp_regression_suite
                WHERE status = 1 AND cron_expr IS NOT NULL AND cron_expr <> ''
                ORDER BY id
                """, (rs, n) -> new SuiteFireCandidate(
                rs.getLong("id"), rs.getLong("project_id"), rs.getLong("created_by"),
                rs.getString("cron_expr"),
                rs.getTimestamp("last_run_at") == null ? null : rs.getTimestamp("last_run_at").toInstant()));
        int fired = 0;
        for (SuiteFireCandidate c : candidates) {
            Optional<Instant> fireTime = ScheduleCronTimes.previousFire(c.cronExpr(), now, window);
            if (fireTime.isEmpty()) {
                continue;
            }
            if (c.lastRunAt() != null && !c.lastRunAt().isBefore(fireTime.get())) {
                continue;
            }
            if (!tryFire(KIND_SUITE, c.id(), c.projectId(), fireTime.get())) {
                continue;
            }
            fireOne(KIND_SUITE, c.id(), c.projectId(), fireTime.get(), now,
                    () -> {
                        SuiteRunResult result = suiteService.run(c.createdBy(), c.id());
                        return "已生成计划「" + result.planName() + "」共 " + result.batchCount()
                                + " 个批次：" + result.message();
                    });
            if (succeeded(KIND_SUITE, c.id(), fireTime.get())) {
                fired++;
            }
        }
        return fired;
    }

    
    private int fireTemplates(Instant now, Duration window) {
        List<TemplateFireCandidate> candidates = jdbcTemplate.query("""
                SELECT id, project_id, created_by, cron_expr, schedule_env_id
                FROM tp_batch_template
                WHERE enabled = 1 AND cron_expr IS NOT NULL AND cron_expr <> ''
                ORDER BY id
                """, (rs, n) -> new TemplateFireCandidate(
                rs.getLong("id"), rs.getLong("project_id"),
                (Long) rs.getObject("created_by"), rs.getString("cron_expr"),
                (Long) rs.getObject("schedule_env_id")));
        int fired = 0;
        for (TemplateFireCandidate c : candidates) {
            Optional<Instant> fireTime = ScheduleCronTimes.previousFire(c.cronExpr(), now, window);
            if (fireTime.isEmpty()) {
                continue;
            }
            if (!tryFire(KIND_TEMPLATE, c.id(), c.projectId(), fireTime.get())) {
                continue;
            }
            fireOne(KIND_TEMPLATE, c.id(), c.projectId(), fireTime.get(), now, () -> {
                if (c.scheduleEnvId() == null) {
                    throw new ServiceException(
                            "定时复用未配置目标环境，请在批次模板中指定定时执行环境后启用",
                            BizErrorCode.VALIDATION_ERROR);
                }
                BatchInfo batch = batchService.createFromTemplate(c.createdBy(),
                        new BatchFromTemplateInput(c.id(), null, c.scheduleEnvId()));
                jdbcTemplate.update("UPDATE tp_exec_batch SET schedule_type = 1 WHERE id = ?", batch.id());
                return "已从模板复用建批「" + batch.name() + "」（批次号 " + batch.batchNo() + "）";
            });
            if (succeeded(KIND_TEMPLATE, c.id(), fireTime.get())) {
                fired++;
            }
        }
        return fired;
    }

    /**
     * Periodic perf-scenario chain (B-8): scenarios with the schedule switch on and a
     * cron pinned fire through the single shared launch entry — the same safety gate,
     * duration ceiling and GA-2 environment lock the manual and CI doors run (zero
     * bypass by construction, since there is only one door). The firing operator is the
     * scenario creator; a pinned schedule_env_id rides the GB-1 launch override.
     */
    private int firePerfScenarios(Instant now, Duration window) {
        List<PerfFireCandidate> candidates = jdbcTemplate.query("""
                SELECT id, project_id, name, schedule_cron, schedule_env_id
                FROM tp_perf_scenario
                WHERE schedule_enabled = 1 AND schedule_cron IS NOT NULL AND schedule_cron <> ''
                ORDER BY id
                """, (rs, n) -> new PerfFireCandidate(
                rs.getLong("id"), rs.getLong("project_id"), rs.getString("name"),
                rs.getString("schedule_cron"),
                rs.getObject("schedule_env_id") == null ? null : rs.getLong("schedule_env_id")));
        int fired = 0;
        for (PerfFireCandidate c : candidates) {
            Optional<Instant> fireTime = ScheduleCronTimes.previousFire(c.cronExpr(), now, window);
            if (fireTime.isEmpty()) {
                continue; // no trigger point inside the window (invalid cron fails closed on save)
            }
            if (!tryFire(KIND_PERF, c.id(), c.projectId(), fireTime.get())) {
                continue; // unique-key clash = another instance/round already claimed this point
            }
            fireOne(KIND_PERF, c.id(), c.projectId(), fireTime.get(), now, () -> {
                // Unattended firing carries no interactive operator: the run row keeps a
                // null operator (the ledger names the scenario and the trigger point).
                long runId = perfRunService.launch(c.projectId(), c.id(), c.scheduleEnvId(), null);
                return "定时压测已发起运行（运行号 " + runId + "，场景「" + c.name() + "」）";
            });
            if (succeeded(KIND_PERF, c.id(), fireTime.get())) {
                fired++;
            }
        }
        return fired;
    }

    
    private void fireOne(String kind, long refId, long projectId, Instant fireTime,
                         Instant now, java.util.function.Supplier<String> action) {
        try {
            String message = action.get();
            recordResult(kind, refId, fireTime, "success", withCatchupNote(message, fireTime, now));
        } catch (Exception e) {
            log.warn("定时触发失败 kind={} ref={}: {}", kind, refId, e.getMessage());
            recordResult(kind, refId, fireTime, "failed", withCatchupNote(e.getMessage(), fireTime, now));
        }
    }

    private boolean succeeded(String kind, long refId, Instant fireTime) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_schedule_fire WHERE kind = ? AND ref_id = ?"
                        + " AND fire_time = ? AND status = 'success'",
                Long.class, kind, refId, Timestamp.from(fireTime));
        return n != null && n > 0;
    }

    
    private String withCatchupNote(String message, Instant fireTime, Instant now) {
        return Duration.between(fireTime, now).compareTo(CATCHUP_MARK) > 0
                ? "（补偿触发：原计划 " + fireTime + "）" + message
                : message;
    }

    @Override
    public boolean tryFire(String kind, long refId, long projectId, Instant fireTime) {
        int inserted = jdbcTemplate.update("""
                INSERT INTO tp_schedule_fire (kind, ref_id, project_id, fire_time, status)
                VALUES (?, ?, ?, ?, 'fired') ON CONFLICT (kind, ref_id, fire_time) DO NOTHING
                """, kind, refId, projectId, Timestamp.from(fireTime));
        return inserted > 0;
    }

    @Override
    public void recordResult(String kind, long refId, Instant fireTime, String status, String message) {
        jdbcTemplate.update("""
                UPDATE tp_schedule_fire SET status = ?, message = ?
                WHERE kind = ? AND ref_id = ? AND fire_time = ?
                """, status, message, kind, refId, Timestamp.from(fireTime));
    }

    /**
     * Trigger columns the ledger list may sort by -> SQL expressions. Only
     * physical columns exist on this table (no jsonb facets), so the map
     * values are plain column names — user input never reaches the SQL
     * string: an off-list or injection-shaped key is refused fail-closed by
     * the shared gate before any SQL is built.
     */
    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "fire_time", "fire_time",
            "kind", "kind",
            "ref_id", "ref_id",
            "status", "status",
            "created_at", "created_at");

    /**
     * ORDER BY builder: a null sort key keeps the historical fire_time DESC
     * order (the ledger reads newest-first by design); a whitelisted key
     * orders by that expression with the row id as tie-breaker so paging
     * stays deterministic under equal timestamps.
     */
    private static String orderClause(String sortBy, String sortDir) {
        String column = SortColumnPolicy.require(sortBy, SORT_COLUMNS, "定时触发留痕");
        if (column == null) {
            return " ORDER BY fire_time DESC, id DESC";
        }
        return " ORDER BY " + column
                + ("asc".equalsIgnoreCase(sortDir) ? " ASC" : " DESC")
                + ", id DESC";
    }

    
    @Override
    public PageResult<ScheduleFireInfo> listFires(ScheduleFireQuery query) {
        int page = Math.max(1, query.page());
        int size = Math.min(200, Math.max(1, query.size() == 0 ? 20 : query.size()));
        StringBuilder where = new StringBuilder(" WHERE project_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(query.projectId());
        if (query.kind() != null && !query.kind().isBlank()) {
            where.append(" AND kind = ?");
            args.add(query.kind().trim());
        }
        if (query.status() != null && !query.status().isBlank()) {
            where.append(" AND status = ?");
            args.add(query.status().trim());
        }
        Long total = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_schedule_fire" + where, Long.class, args.toArray());
        List<Object> pageArgs = new java.util.ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((page - 1) * size);
        List<ScheduleFireInfo> list = jdbcTemplate.query("""
                SELECT id, kind, ref_id, project_id,
                       to_char(fire_time, 'YYYY-MM-DD HH24:MI:SS') AS fire_time,
                       status, message,
                       to_char(created_at, 'YYYY-MM-DD HH24:MI:SS') AS created_at
                FROM tp_schedule_fire""" + where
                + orderClause(query.sortBy(), query.sortDir()) + " LIMIT ? OFFSET ?",
                (rs, n) -> new ScheduleFireInfo(rs.getLong("id"), rs.getString("kind"),
                        rs.getLong("ref_id"), rs.getLong("project_id"),
                        rs.getString("fire_time"), rs.getString("status"),
                        rs.getString("message"), rs.getString("created_at")),
                pageArgs.toArray());
        return new PageResult<>(list, total == null ? 0 : total);
    }
}
