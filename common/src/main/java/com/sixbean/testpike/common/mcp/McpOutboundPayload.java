package com.sixbean.testpike.common.mcp;

/**
 * One prepared MCP outbound payload : the body actually handed to
 * the remote server plus the honest audit facet telling whether it was
 * desensitized on the way out. Independent class file per project rules; this
 * type is the only shape the senders are allowed to transmit, so a caller
 * cannot quietly skip the gate. Shared between ai-service (agent tool bags)
 * and core (knowledge sync chain) as the single source per the
 * extract-on-second-consumer rule.
 *
 * @param body outbound request body (masked text when the gate is on,
 * raw text when the enterprise downgrade key is off)
 * @param masked 1 = body left desensitized, 0 = body left untouched because
 * the gate is off (recorded honestly, never silently)
 */
public record McpOutboundPayload(String body, int masked) {
}
