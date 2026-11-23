package com.sixbean.testpike.core.aichat.domain.service.impl;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.aichat.api.dto.ChatSessionUsageItemView;
import com.sixbean.testpike.core.aichat.api.dto.ChatSessionUsagePageView;
import com.sixbean.testpike.core.aichat.domain.service.ChatUsageAttributionService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Session-attribution read model (E9 cost dashboard, AO batch phase 3).
 * <p>House style: NamedParameterJdbcTemplate, read-only, no transaction — the
 * one message-row scan is the reconciliation denominator the dashboard
 * asserts against, so it recomputes from {@code tp_ai_chat_message} instead
 * of trusting the session ledger alone. Rows exist only where the window
 * actually carried usage (a session whose every turn was aged out claims
 * nothing this month); the lifetime ledger travels beside the window sum as
 * an honest second column, never merged.
 */
@Service
public class ChatUsageAttributionServiceImpl implements ChatUsageAttributionService {

    /** Exact yyyy-MM key — anything looser is a caller bug, refused fail-closed. */
    private static final Pattern MONTH_KEY = Pattern.compile("\\d{4}-\\d{2}");
    private static final int MAX_PAGE_SIZE = 100;

    private final NamedParameterJdbcTemplate named;

    public ChatUsageAttributionServiceImpl(NamedParameterJdbcTemplate named) {
        this.named = named;
    }

    @Override
    public ChatSessionUsagePageView usageAttribution(String month, Long projectId,
                                                     int page, int pageSize) {
        if (month == null || !MONTH_KEY.matcher(month).matches()) {
            throw new ServiceException("月份参数必须是 yyyy-MM（如 2026-10）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (page < 1) {
            throw new ServiceException("页码从 1 开始", BizErrorCode.VALIDATION_ERROR);
        }
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new ServiceException("每页条数只能是 1~" + MAX_PAGE_SIZE,
                    BizErrorCode.VALIDATION_ERROR);
        }
        YearMonth window = parseMonth(month);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("start", window.atDay(1).atStartOfDay())
                .addValue("end", window.plusMonths(1).atDay(1).atStartOfDay())
                .addValue("projectId", projectId);

        String from = """
                FROM (
                    SELECT m.session_id,
                           count(*) FILTER (WHERE m.role = 'assistant'
                                AND (m.prompt_tokens > 0 OR m.completion_tokens > 0)) AS turns,
                           COALESCE(SUM(m.prompt_tokens + m.completion_tokens), 0) AS window_tokens
                    FROM tp_ai_chat_message m
                    WHERE m.created_at >= :start AND m.created_at < :end
                    GROUP BY m.session_id
                ) agg
                JOIN tp_ai_chat_session s ON s.id = agg.session_id
                JOIN tp_project p ON p.id = s.project_id
                JOIN tp_user u ON u.id = s.user_id
                WHERE agg.turns > 0
                  AND (CAST(:projectId AS bigint) IS NULL OR s.project_id = CAST(:projectId AS bigint))
                """;

        long total = named.queryForObject("SELECT count(*) " + from, params, Long.class);
        List<ChatSessionUsageItemView> rows = named.query(
                "SELECT s.id AS session_id, s.project_id, p.name AS project_name, "
                        + "u.display_name AS user_name, s.title, s.source, "
                        + "agg.turns AS assistant_turns, agg.window_tokens, "
                        + "s.total_tokens AS session_total_tokens "
                        + from
                        + " ORDER BY agg.window_tokens DESC, s.id DESC LIMIT :limit OFFSET :offset",
                params.addValue("limit", pageSize)
                        .addValue("offset", (long) (page - 1) * pageSize),
                (rs, rowNum) -> new ChatSessionUsageItemView(
                        rs.getLong("session_id"), rs.getLong("project_id"),
                        rs.getString("project_name"), rs.getString("user_name"),
                        rs.getString("title"), rs.getInt("source"),
                        rs.getLong("assistant_turns"), rs.getLong("window_tokens"),
                        rs.getLong("session_total_tokens")));
        return new ChatSessionUsagePageView(rows, total, page, pageSize);
    }

    private static YearMonth parseMonth(String month) {
        try {
            return YearMonth.parse(month, DateTimeFormatter.ofPattern("yyyy-MM"));
        } catch (DateTimeParseException e) {
            // A syntactically shaped but impossible month (2026-13) lands here.
            throw new ServiceException("月份参数必须是 yyyy-MM（如 2026-10）",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }
}
