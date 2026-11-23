package com.sixbean.testpike.core.sched.domain.service.impl;

import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.common.domain.component.SortColumnPolicy;
import com.sixbean.testpike.core.sched.api.dto.SchedulerHealthInfo;
import com.sixbean.testpike.core.sched.domain.component.SchedulerJobCatalog;
import com.sixbean.testpike.core.sched.domain.service.SchedulerHealthService;
import org.springframework.jdbc.core.namedparam.EmptySqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;


@Service
public class SchedulerHealthServiceImpl implements SchedulerHealthService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final NamedParameterJdbcTemplate jdbc;
    private final SchedulerJobCatalog catalog;

    public SchedulerHealthServiceImpl(NamedParameterJdbcTemplate jdbc, SchedulerJobCatalog catalog) {
        this.jdbc = jdbc;
        this.catalog = catalog;
    }

    @Override
    public PageResult<SchedulerHealthInfo> health(String keyword, int page, int size,
                                                  String sortBy, String sortDir) {
        int pageNo = Math.max(1, page);
        int pageSize = Math.min(200, Math.max(1, size == 0 ? 20 : size));

        Map<String, LockRow> locks = new LinkedHashMap<>();
        jdbc.query("""
                SELECT name, locked_by,
                       CAST(locked_at AS TIMESTAMP) AS locked_at,
                       CAST(lock_until AS TIMESTAMP) AS lock_until,
                       lock_until > now() AS running
                FROM shedlock
                """, EmptySqlParameterSource.INSTANCE,
                (rs, n) -> {
                    locks.put(rs.getString("name"), new LockRow(
                            rs.getString("locked_by"),
                            fmt(rs.getTimestamp("locked_at")),
                            fmt(rs.getTimestamp("lock_until")),
                            rs.getBoolean("running")));
                    return Boolean.TRUE;
                });

        List<SchedulerHealthInfo> rows = new ArrayList<>();
        for (String jobName : catalog.descriptions().keySet()) {
            rows.add(view(jobName, locks.get(jobName)));
        }
        for (Map.Entry<String, LockRow> extra : locks.entrySet()) {
            if (!catalog.descriptions().containsKey(extra.getKey())) {
                rows.add(view(extra.getKey(), extra.getValue()));
            }
        }

        String kw = keyword == null ? "" : keyword.trim().toLowerCase();
        List<SchedulerHealthInfo> filtered = kw.isEmpty() ? rows : rows.stream()
                .filter(r -> r.jobName().toLowerCase().contains(kw)
                        || r.description().toLowerCase().contains(kw))
                .toList();
        List<SchedulerHealthInfo> ordered = orderRows(filtered, sortBy, sortDir);
        int from = Math.min((pageNo - 1) * pageSize, ordered.size());
        int to = Math.min(from + pageSize, ordered.size());
        return new PageResult<>(ordered.subList(from, to), ordered.size());
    }

    /**
     * Composed-row sort keys the health card may order by. The catalog x
     * shedlock merge lives in memory (row count is constant at job-catalog
     * scale), so there is no SQL ORDER BY to guard — but the same shared
     * whitelist gate still decides legality fail-closed: only these keys
     * select a comparator, an injection-shaped or off-list name is refused
     * with the allowed list named in plain words before any row is returned.
     */
    private static final Map<String, String> SORT_KEYS = Map.of(
            "job_name", "job_name",
            "description", "description",
            "locked_by", "locked_by",
            "locked_at", "locked_at",
            "lock_until", "lock_until");

    /**
     * Order the composed window: no key keeps the catalog registration order
     * (the historical view); a whitelisted key compares its rendered value
     * with never-run (null) rows last in both directions — a locked-at sort
     * should surface the most recent activity, not invent one — and the job
     * name as tie-breaker so paging windows stay deterministic.
     */
    private static List<SchedulerHealthInfo> orderRows(List<SchedulerHealthInfo> rows,
                                                       String sortBy, String sortDir) {
        String key = SortColumnPolicy.require(sortBy, SORT_KEYS, "调度健康卡");
        if (key == null) {
            return rows;
        }
        Function<SchedulerHealthInfo, String> value = switch (key) {
            case "job_name" -> SchedulerHealthInfo::jobName;
            case "description" -> SchedulerHealthInfo::description;
            case "locked_by" -> SchedulerHealthInfo::lockedBy;
            case "locked_at" -> SchedulerHealthInfo::lockedAt;
            default -> SchedulerHealthInfo::lockUntil;
        };
        // Timestamp strings share one yyyy-MM-dd HH:mm:ss format, so the
        // lexicographic order equals the chronological order here.
        Comparator<String> direction = "asc".equalsIgnoreCase(sortDir)
                ? Comparator.nullsLast(Comparator.naturalOrder())
                : Comparator.nullsLast(Comparator.reverseOrder());
        List<SchedulerHealthInfo> sorted = new ArrayList<>(rows);
        sorted.sort(Comparator.comparing(value, direction)
                .thenComparing(SchedulerHealthInfo::jobName));
        return sorted;
    }

    private SchedulerHealthInfo view(String jobName, LockRow lock) {
        return new SchedulerHealthInfo(jobName, catalog.describe(jobName),
                lock == null ? null : lock.lockedBy(),
                lock == null ? null : lock.lockedAt(),
                lock == null ? null : lock.lockUntil(),
                lock != null && lock.running());
    }

    private static String fmt(Timestamp ts) {
        return ts == null ? null : TS.format(ts.toLocalDateTime());
    }
}
