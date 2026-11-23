package com.sixbean.testpike.core.deadletter.domain.service.impl;

import com.sixbean.testpike.common.exception.ValidationException;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.common.domain.component.PageQueryPolicy;
import com.sixbean.testpike.core.deadletter.api.dto.DeadLetterBrowseQuery;
import com.sixbean.testpike.core.deadletter.api.dto.DeadLetterClassCount;
import com.sixbean.testpike.core.deadletter.api.dto.DeadLetterLedgerRow;
import com.sixbean.testpike.core.deadletter.api.dto.DeadLetterQueueCount;
import com.sixbean.testpike.core.deadletter.domain.component.DeadLetterFailureClass;
import com.sixbean.testpike.core.deadletter.domain.component.DeadLetterLedgerStatus;
import com.sixbean.testpike.core.deadletter.domain.service.DeadLetterLedgerQueryService;
import com.sixbean.testpike.core.deadletter.infrastructure.dataobject.DeadLetterLedgerBrowseRow;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Ledger read path over tp_mq_dead_letter .
 * <p>NamedParameterJdbcTemplate base path with BeanPropertyRowMapper for the browse
 * rows (dataobject -> api record via toInfo), lightweight RowMapper lambdas for the
 * two group-count shapes. All three queries filter on ledger columns only — the
 * <p>Filter literals fail closed: status resolves through {@link DeadLetterLedgerStatus}
 * and class through {@link DeadLetterFailureClass}; an unrecognized value throws a
 * human-readable ValidationException rather than silently returning an empty result.
 */
@Service
public class DeadLetterLedgerQueryServiceImpl implements DeadLetterLedgerQueryService {

    private static final Set<String> FAILURE_CLASS_NAMES = Arrays.stream(DeadLetterFailureClass.values())
            .map(Enum::name)
            .collect(Collectors.toUnmodifiableSet());

    /** ISO-8601 UTC rendering stays in SQL so the api record transports a plain string. */
    private static final String SELECT_BROWSE = """
            SELECT id, message_fingerprint, queue_name, orig_exchange, orig_routing_key,
                   payload_json, failure_class, failure_reason, replay_round, status,
                   void_reason, operator,
                   to_char(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"') AS created_at_utc,
                   to_char(updated_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"') AS updated_at_utc
            FROM tp_mq_dead_letter
            """;

    private final NamedParameterJdbcTemplate namedJdbc;

    public DeadLetterLedgerQueryServiceImpl(NamedParameterJdbcTemplate namedJdbc) {
        this.namedJdbc = namedJdbc;
    }

    @Override
    public List<DeadLetterQueueCount> queueBacklog(String status) {
        String statusValue = resolveStatusOrDefault(status);
        String sql = """
                SELECT queue_name, count(*) AS row_count
                FROM tp_mq_dead_letter
                WHERE status = :status
                GROUP BY queue_name
                HAVING count(*) > 0
                ORDER BY count(*) DESC, queue_name ASC
                """;
        return namedJdbc.query(sql, new MapSqlParameterSource("status", statusValue),
                (rs, rowNum) -> new DeadLetterQueueCount(rs.getString("queue_name"),
                        rs.getLong("row_count")));
    }

    @Override
    public List<DeadLetterClassCount> classDistribution(String queueName, String status) {
        String statusValue = resolveStatusOrDefault(status);
        String queueFilter = blankToNull(queueName) == null ? "" : " AND queue_name = :queueName";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("status", statusValue)
                .addValue("queueName", blankToNull(queueName));
        String sql = "SELECT failure_class, count(*) AS row_count"
                + " FROM tp_mq_dead_letter WHERE status = :status" + queueFilter
                + " GROUP BY failure_class ORDER BY count(*) DESC, failure_class ASC";
        return namedJdbc.query(sql, params,
                (rs, rowNum) -> new DeadLetterClassCount(rs.getString("failure_class"),
                        rs.getLong("row_count")));
    }

    @Override
    public PageResult<DeadLetterLedgerRow> browse(DeadLetterBrowseQuery query) {
        String statusValue = resolveStatusOrDefault(query.status());
        String queue = blankToNull(query.queueName());
        String failureClass = normalizeFailureClass(query.failureClass());
        int safeSize = PageQueryPolicy.enforceSize(query.size());
        long offset = (long) Math.max(query.page() - 1, 0) * safeSize;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("status", statusValue)
                .addValue("queueName", queue)
                .addValue("failureClass", failureClass)
                .addValue("size", safeSize)
                .addValue("offset", offset);

        String where = " WHERE status = :status"
                + (queue == null ? "" : " AND queue_name = :queueName")
                + (failureClass == null ? "" : " AND failure_class = :failureClass");

        Long total = namedJdbc.queryForObject(
                "SELECT count(*) FROM tp_mq_dead_letter" + where, params, Long.class);
        RowMapper<DeadLetterLedgerBrowseRow> mapper =
                new org.springframework.jdbc.core.BeanPropertyRowMapper<>(DeadLetterLedgerBrowseRow.class);
        List<DeadLetterLedgerRow> rows = namedJdbc.query(
                SELECT_BROWSE + where + " ORDER BY created_at DESC, id DESC LIMIT :size OFFSET :offset",
                params, mapper)
                .stream().map(DeadLetterLedgerBrowseRow::toInfo).toList();
        return new PageResult<>(rows, total != null ? total : 0L);
    }

    /** Null/blank means "the backlog view" = pending; anything else must be a known literal. */
    private String resolveStatusOrDefault(String status) {
        String trimmed = blankToNull(status);
        return trimmed == null
                ? DeadLetterLedgerStatus.PENDING.dbValue()
                : DeadLetterLedgerStatus.fromFilter(trimmed).dbValue();
    }

    private String normalizeFailureClass(String failureClass) {
        String trimmed = blankToNull(failureClass);
        if (trimmed == null) {
            return null;
        }
        String upper = trimmed.toUpperCase();
        if (!FAILURE_CLASS_NAMES.contains(upper)) {
            throw new ValidationException("死信失败分型仅支持 "
                    + String.join("/", FAILURE_CLASS_NAMES.stream().sorted().toList())
                    + "，当前请求：" + failureClass);
        }
        return upper;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
