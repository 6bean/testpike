package com.sixbean.testpike.core.tool.domain.service.impl;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.common.domain.component.PageQueryPolicy;
import com.sixbean.testpike.core.tool.api.dto.ToolInvocationInfo;
import com.sixbean.testpike.core.tool.api.dto.ToolInvocationQuery;
import com.sixbean.testpike.core.tool.domain.service.ToolInvocationService;
import com.sixbean.testpike.core.tool.infrastructure.dataobject.ToolInvocationRow;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;


@Service
public class ToolInvocationServiceImpl implements ToolInvocationService {

    
    static final int MAX_SPAN_DAYS = 90;
    
    private static final int MAX_OFFSET = 100_000;
    
    private static final String SELECT_COLUMNS = """
            SELECT i.id, i.caller, i.actor_id, i.task_id, i.tool_id, i.server_id,
                   i.tool_name, i.success, i.duration_ms, i.error, i.digest_hash, i.created_at
            FROM tp_tool_invocation i
            """;

    private final NamedParameterJdbcTemplate named;

    public ToolInvocationServiceImpl(NamedParameterJdbcTemplate named) {
        this.named = named;
    }

    @Override
    public PageResult<ToolInvocationInfo> list(ToolInvocationQuery query) {
        LocalDateTime start = parseTime(query.start(), "起始时间");
        LocalDateTime end = parseTime(query.end(), "截止时间");
        if (start != null && end != null) {
            if (start.isAfter(end)) {
                throw new ServiceException("起始时间不能晚于截止时间（起始 " + start + " 晚于截止 " + end + "）——"
                        + "请将「起始时间」调为早于「截止时间」（格式：yyyy-MM-dd HH:mm:ss）后重新查询",
                        BizErrorCode.VALIDATION_ERROR);
            }
            if (Duration.between(start, end).toDays() > MAX_SPAN_DAYS) {
                throw new ServiceException("查询时间跨度不能超过 " + MAX_SPAN_DAYS
                        + " 天（本次跨度 " + Duration.between(start, end).toDays() + " 天）——"
                        + "请将「起始时间」「截止时间」收窄到 " + MAX_SPAN_DAYS + " 天内后重新查询",
                        BizErrorCode.VALIDATION_ERROR);
            }
        }

        Integer exists = named.queryForObject(
                "SELECT count(*) FROM tp_tool WHERE id = :toolId",
                new MapSqlParameterSource("toolId", query.toolId()), Integer.class);
        if (exists == null || exists == 0) {
            throw new ServiceException("工具不存在（id=" + query.toolId() + "）",
                    BizErrorCode.NOT_FOUND);
        }

        int safePage = Math.max(query.page(), 1);
        int safeSize = PageQueryPolicy.enforceSize(query.size());
        long offset = PageQueryPolicy.clampOffset(safePage, safeSize, MAX_OFFSET);

        MapSqlParameterSource params = new MapSqlParameterSource("toolId", query.toolId());
        StringBuilder where = new StringBuilder(" WHERE i.tool_id = :toolId");
        if (start != null) {
            where.append(" AND i.created_at >= :start");
            params.addValue("start", start);
        }
        if (end != null) {
            where.append(" AND i.created_at <= :end");
            params.addValue("end", end);
        }
        Long total = named.queryForObject(
                "SELECT count(*) FROM tp_tool_invocation i" + where, params, Long.class);
        params.addValue("size", safeSize).addValue("offset", offset);
        List<ToolInvocationInfo> list = named.query(
                SELECT_COLUMNS + where + " ORDER BY i.created_at DESC, i.id DESC"
                        + " LIMIT :size OFFSET :offset",
                params, new BeanPropertyRowMapper<>(ToolInvocationRow.class))
                .stream().map(ToolInvocationRow::toInfo).toList();
        return new PageResult<>(list, total != null ? total : 0L);
    }

    
    private LocalDateTime parseTime(String text, String label) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(text.trim());
        } catch (DateTimeParseException e) {
            throw new ServiceException(label + "格式不正确（" + text + "），"
                    + "请使用 ISO 时间格式，如 2026-09-11T00:00", BizErrorCode.VALIDATION_ERROR);
        }
    }
}
