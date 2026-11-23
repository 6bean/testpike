package com.sixbean.testpike.core.mcp.domain.service.impl;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.mcp.api.dto.McpTokenUsageView;
import com.sixbean.testpike.core.mcp.domain.service.McpTokenUsageService;
import com.sixbean.testpike.core.notification.domain.component.AdminAlertRecipients;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * GA3 per-token usage roll-up over the MCP invocation ledger (segment S2b).
 * <p>Read path only: tp_tool_invocation stays the single source, aggregated
 * live by the token attribution column written on the invoke path (S2a).
 * percentile_cont needs an explicit BIGINT to double cast — pinning that in
 * SQL here keeps caller-side maths out of it; NULL durations (calls that
 * failed before latency was measured) drop out of the percentile but still
 * count toward calls/failures.
 */
@Service
public class McpTokenUsageServiceImpl implements McpTokenUsageService {

    private final NamedParameterJdbcTemplate named;
    private final AdminAlertRecipients adminAlertRecipients;

    public McpTokenUsageServiceImpl(NamedParameterJdbcTemplate named,
                                    AdminAlertRecipients adminAlertRecipients) {
        this.named = named;
        this.adminAlertRecipients = adminAlertRecipients;
    }

    @Override
    public McpTokenUsageView usage(long tokenId, long operatorId) {
        List<Long> owners = named.queryForList(
                "SELECT user_id FROM tp_api_token WHERE id = :tokenId",
                new MapSqlParameterSource("tokenId", tokenId), Long.class);
        if (owners.isEmpty()) {
            throw new ServiceException("该 API Token 不存在（tokenId=" + tokenId + "）：请到「API Token」页点击目标令牌行查看调用统计，或修正参数 tokenId 后重查", BizErrorCode.VALIDATION_ERROR);
        }
        if (!owners.get(0).equals(operatorId)
                && !adminAlertRecipients.adminUserIds().contains(operatorId)) {
            throw new ServiceException("只能查看本人签发的 API Token 的调用统计：请切换到签发该令牌的登录身份后进入「API Token」页查看；如需代看请联系系统管理员",
                    BizErrorCode.FORBIDDEN);
        }
        Map<String, Object> row = named.queryForMap("""
                SELECT COUNT(*) AS calls,
                       COUNT(*) FILTER (WHERE success = 0) AS failures,
                       percentile_cont(0.95) WITHIN GROUP (ORDER BY duration_ms::double precision)
                           AS p95_duration_ms
                FROM tp_tool_invocation
                WHERE api_token_id = :tokenId
                """, new MapSqlParameterSource("tokenId", tokenId));
        return new McpTokenUsageView(tokenId,
                ((Number) row.get("calls")).longValue(),
                ((Number) row.get("failures")).longValue(),
                (Double) row.get("p95_duration_ms"));
    }
}
