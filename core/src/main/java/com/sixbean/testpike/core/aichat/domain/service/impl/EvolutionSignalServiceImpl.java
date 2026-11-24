package com.sixbean.testpike.core.aichat.domain.service.impl;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.aichat.api.dto.EvolutionSignalView;
import com.sixbean.testpike.core.aichat.domain.service.EvolutionSignalService;
import com.sixbean.testpike.core.tool.domain.component.ExternalCallErrorClass;
import com.sixbean.testpike.core.tool.domain.component.ExternalCallErrorClassifier;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Observation-signal rollup for the self-evolution loop (AF-i phase 2).
 * <p>House style: NamedParameterJdbcTemplate, read-only, no transaction, no
 * new observation table — every number recomputes from ledgers that already
 * carry the truth: sealed dialog turns and their thumbs columns on
 * {@code tp_ai_chat_message}, session ownership on {@code tp_ai_chat_session},
 * and the tool trail on {@code tp_tool_invocation}. Dialog signals are
 * project-scoped like every other segment door; the tool-failure ledger is
 * platform-wide (invocations carry no project column) and travels the same
 * window, which the DTO wording states plainly instead of hiding.
 * <p>A sealed turn is any assistant row past streaming (done / error /
 * aborted) — ratings only land on sealed rows, so the same shape is the
 * denominator for the follow-up rate. A follow-up is a later user message
 * inside the same window and session: "the answer did not land" proxy.
 */
@Service
public class EvolutionSignalServiceImpl implements EvolutionSignalService {

    /** Exact yyyy-MM key — anything looser is a caller bug, refused fail-closed. */
    private static final Pattern MONTH_KEY = Pattern.compile("\\d{4}-\\d{2}");
    /** Failure groups the rollup reports; heaviest first, the tail is noise for a proposal run. */
    private static final int MAX_TOOL_FAILURE_GROUPS = 100;
    private static final DateTimeFormatter MONTH_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM");

    private final NamedParameterJdbcTemplate named;
    private final ExternalCallErrorClassifier externalCallErrorClassifier;

    public EvolutionSignalServiceImpl(NamedParameterJdbcTemplate named,
                                      ExternalCallErrorClassifier externalCallErrorClassifier) {
        this.named = named;
        this.externalCallErrorClassifier = externalCallErrorClassifier;
    }

    @Override
    public EvolutionSignalView observe(String month, Long projectId) {
        if (month == null || !MONTH_KEY.matcher(month).matches()) {
            throw invalidMonth(month);
        }
        YearMonth window = parseMonth(month);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("start", window.atDay(1).atStartOfDay())
                .addValue("end", window.plusMonths(1).atDay(1).atStartOfDay())
                .addValue("projectId", projectId);

        String scopedSessionJoin = """
                FROM tp_ai_chat_message m
                JOIN tp_ai_chat_session s ON s.id = m.session_id
                WHERE m.role = 'assistant'
                  AND m.created_at >= :start AND m.created_at < :end
                  AND (CAST(:projectId AS bigint) IS NULL OR s.project_id = CAST(:projectId AS bigint))
                """;

        long answered = named.queryForObject(
                "SELECT count(DISTINCT m.session_id) " + scopedSessionJoin
                        + "AND m.status <> 'streaming'",
                params, Long.class);
        long followUps = named.queryForObject(
                "SELECT count(DISTINCT m.session_id) " + scopedSessionJoin
                        + """
                          AND m.status <> 'streaming'
                          AND EXISTS (
                              SELECT 1 FROM tp_ai_chat_message later
                              WHERE later.session_id = m.session_id
                                AND later.role = 'user' AND later.id > m.id
                                AND later.created_at >= :start AND later.created_at < :end
                          )
                          """,
                params, Long.class);

        List<long[]> ledgers = named.query(
                "SELECT count(*) FILTER (WHERE m.feedback = 1) AS up, "
                        + "count(*) FILTER (WHERE m.feedback = -1) AS down, "
                        + "count(*) FILTER (WHERE m.status = 'error') AS errors "
                        + scopedSessionJoin,
                params,
                (rs, rowNum) -> new long[]{rs.getLong("up"), rs.getLong("down"), rs.getLong("errors")});
        long[] counts = ledgers.isEmpty() ? new long[]{0, 0, 0} : ledgers.get(0);

        List<EvolutionSignalView.ToolFailureRow> failures = named.query(
                """
                SELECT tool_name, count(*) AS failure_count,
                       (ARRAY_AGG(error ORDER BY created_at DESC, id DESC)
                            FILTER (WHERE error IS NOT NULL))[1] AS last_error
                FROM tp_tool_invocation
                WHERE success = 0
                  AND created_at >= :start AND created_at < :end
                GROUP BY tool_name
                ORDER BY failure_count DESC, tool_name
                LIMIT :cap
                """,
                params.addValue("cap", MAX_TOOL_FAILURE_GROUPS),
                (rs, rowNum) -> new EvolutionSignalView.ToolFailureRow(
                        rs.getString("tool_name"), rs.getLong("failure_count"),
                        rs.getString("last_error")));

        double followUpRate = answered == 0 ? 0.0 : (double) followUps / answered;
        return new EvolutionSignalView(month, projectId, answered, followUps, followUpRate,
                counts[0], counts[1], counts[2], failures, externalCallErrorRollup(params));
    }

    /**
 * GA5 : the failed external /mcp calls inside the window, bucketed
 * by error class. Read-side only — the gate answer was already recorded
 * verbatim on {@code tp_tool_invocation.error} by the /mcp trail, the
 * classifier just re-reads it, so the write gate and every writer stay
 * untouched. Distinct error texts are grouped first, so the classification
 * work scales with the variety of refusals, not with traffic volume; the
 * caller dimension is pinned to 'mcp' because "external call" is exactly
 * the agent-facing door (ai-service's own legs travel a different ledger
 * segment). All declared classes appear, zeros included: the shape of the
 * distribution must not depend on which vocabulary happened to match.
 */
    private List<EvolutionSignalView.ExternalCallErrorRow> externalCallErrorRollup(
            MapSqlParameterSource params) {
        Map<ExternalCallErrorClass, Long> buckets = new EnumMap<>(ExternalCallErrorClass.class);
        for (ExternalCallErrorClass each : ExternalCallErrorClass.values()) {
            buckets.put(each, 0L);
        }
        named.query(
                """
                SELECT error, count(*) AS failure_rows
                FROM tp_tool_invocation
                WHERE caller = 'mcp'
                  AND success = 0
                  AND created_at >= :start AND created_at < :end
                GROUP BY error
                """,
                params,
                (rs, rowNum) -> buckets.merge(
                        externalCallErrorClassifier.classify(rs.getString("error")),
                        rs.getLong("failure_rows"), Long::sum));
        return buckets.entrySet().stream()
                .map(entry -> new EvolutionSignalView.ExternalCallErrorRow(entry.getKey(), entry.getValue()))
                .toList();
    }

    private static ServiceException invalidMonth(String raw) {
        return new ServiceException("月份参数不合法：收到「"
                + (raw == null ? "空值" : raw)
                + "」，请改成 yyyy-MM 形式的月份（正确写法示例 2026-10）后重新查询",
                BizErrorCode.VALIDATION_ERROR);
    }

    private static YearMonth parseMonth(String month) {
        try {
            return YearMonth.parse(month, MONTH_FORMAT);
        } catch (DateTimeParseException e) {
            // A syntactically shaped but impossible month (2026-13) lands here.
            throw invalidMonth(month);
        }
    }
}
