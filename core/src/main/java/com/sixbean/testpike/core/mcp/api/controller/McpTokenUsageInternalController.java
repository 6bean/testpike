package com.sixbean.testpike.core.mcp.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.audit.domain.component.OperatorContext;
import com.sixbean.testpike.core.mcp.api.dto.McpTokenUsageView;
import com.sixbean.testpike.core.mcp.domain.service.McpTokenUsageService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal read endpoint for the GA3 per-token usage roll-up (segment S2b).
 * <p>Consumed by admin's MCP token surfaces; ownership and admin read-through
 * are decided once inside {@link McpTokenUsageService}, so the bridge stays
 * a pure pass-through with zero re-implementation (layering rule: checks do
 * not get copied into admin).
 */
@RestController
@RequestMapping("/internal/mcp-tokens")
public class McpTokenUsageInternalController {

    private final McpTokenUsageService mcpTokenUsageService;

    public McpTokenUsageInternalController(McpTokenUsageService mcpTokenUsageService) {
        this.mcpTokenUsageService = mcpTokenUsageService;
    }

    /** Calls / failures / p95 duration attributed to one API token. */
    @GetMapping("/{tokenId}/usage")
    public Response<McpTokenUsageView> usage(@PathVariable long tokenId) {
        Long operatorId = OperatorContext.currentOperatorId();
        if (operatorId == null) {
            throw new ServiceException("缺少操作人身份：调用统计需登录后查看，请先登录系统再进入「API Token」页打开目标令牌行，或携带有效登录凭据重发请求",
                    BizErrorCode.FORBIDDEN);
        }
        return Response.builderSuccess(mcpTokenUsageService.usage(tokenId, operatorId));
    }
}
