package com.sixbean.testpike.core.mcp.domain.service;

import com.sixbean.testpike.core.mcp.api.dto.McpTokenUsageView;

/**
 * Read-only per-token MCP usage roll-up (GA3, segment S2b).
 * <p>Ownership enforcement lives here (core = single point): the token owner
 * may read their own numbers, system admins may read any token, everybody
 * else is refused with an honest message.
 */
public interface McpTokenUsageService {

    /**
     * Aggregate the invocation ledger for one API token.
     *
     * @param tokenId     the tp_api_token row id (NULL-attributed rows excluded)
     * @param operatorId  the authenticated operator asking for the numbers
     * @return calls / failures / p95 duration for that token
     */
    McpTokenUsageView usage(long tokenId, long operatorId);
}
