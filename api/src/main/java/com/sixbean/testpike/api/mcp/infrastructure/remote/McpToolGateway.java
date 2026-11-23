package com.sixbean.testpike.api.mcp.infrastructure.remote;

import java.util.List;
import java.util.Map;


public interface McpToolGateway {

    /**
 *
 * @param caller authenticated owner id plus the GA1 project pin, threaded to
 * both gate points (pinned tokens are refused off-scope with
 * zero core hops)
 */
    String invoke(String toolName, Map<String, Object> arguments, McpCaller caller);

    /**
     * The catalog-derived pass-through tools this deployment currently exposes.
     *
     * <p>Fetched from core rather than listed here: core owns the interface catalog, so a new
     * endpoint appears on the external face when the door allows it, with no second list to keep
     * aligned. An empty list is a legitimate answer (the exposure switch is off by default).
     */
    List<McpPassThroughRemoteTool> listPassThroughTools();

    /**
     * Serve one pass-through read by catalog key (the door applies membership, budget and width).
     *
     * @param endpointKey  catalog key the tool was projected from
     * @param arguments    schema-checked arguments, forwarded as declared parameters
     * @param operatorUserId authenticated token owner, never a caller-supplied identity
     */
    String invokePassThrough(String endpointKey, Map<String, Object> arguments, long operatorUserId);

    /**
 * GA3 per-token usage roll-up for the external MCP face : one
 * hop to core's aggregate read door over the invocation ledger. The token row
 * id arrives from the authenticated principal, never from tool arguments, so
 * an agent can only read the ledger of the token it presented; ownership and
 * delegated reads stay decided once inside core.
 */
    String tokenUsage(long tokenId, long operatorUserId);
}
