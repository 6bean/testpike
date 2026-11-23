package com.sixbean.testpike.api.mcp.domain.component;

import io.modelcontextprotocol.common.McpTransportContext;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Single source for the channels an MCP client may use to carry the idempotency key of a write tool.
 *
 * <p>The HTTP {@code Idempotency-Key} header is what the open API contract names, and the transport
 * layer parks it in {@link McpContextKeys#IDEMPOTENCY_KEY}. That channel is unreachable for a
 * generic MCP bridge though: its configuration holds one static Bearer token per server and offers
 * no per-request header slot. Evidence = the dual real-client L4 run of 2026-11-18, where the agent
 * retried {@code doc.upload} six times against the header-only guard, exhausted its iteration budget
 * and never reached the write door — a rejection that names an unusable channel is a dead end, not a
 * fix instruction.
 *
 * <p>The obligation itself is unchanged: a write tool carrying no key on any channel is still
 * rejected and never reaches the gateway. What widens is the supply — the key may also travel as the
 * {@link #ARGUMENT} property of the argument bag (advertised in every write tool's input schema), or
 * inside the {@link #META_ARGUMENT} metadata object under {@link #META_KEY}, the MCP spec slot agents
 * already guess at. The header keeps priority when a client happens to send both.
 *
 */
public final class McpIdempotencyKey {

    /** Argument name of the idempotency key, published in write-tool input schemas. */
    public static final String ARGUMENT = "idempotency_key";

    /** MCP metadata object inside the argument bag (spec: {@code _meta}). */
    public static final String META_ARGUMENT = "_meta";

    /** Key slot within {@link #META_ARGUMENT} (spec naming is kebab-case). */
    public static final String META_KEY = "idempotency-key";

    /**
     * Resolve the key from the transport header first, then the two argument channels.
     *
     * @return the trimmed key, or {@code null} when no channel supplied a usable value
     */
    public static String resolve(McpTransportContext ctx, Map<String, Object> arguments) {
        String fromHeader = headerValue(ctx);
        if (fromHeader != null) {
            return fromHeader;
        }
        String fromArgument = textValue(arguments == null ? null : arguments.get(ARGUMENT));
        if (fromArgument != null) {
            return fromArgument;
        }
        Object meta = arguments == null ? null : arguments.get(META_ARGUMENT);
        if (meta instanceof Map<?, ?> metaMap) {
            return textValue(metaMap.get(META_KEY));
        }
        return null;
    }

    /**
     * Copy the argument bag without the transport-level channels, so the payload forwarded to core
     * and written to the audit ledger holds only tool-declared business arguments.
     */
    public static Map<String, Object> withoutTransportKeys(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return Map.of();
        }
        if (!arguments.containsKey(ARGUMENT) && !arguments.containsKey(META_ARGUMENT)) {
            return arguments;
        }
        Map<String, Object> business = new LinkedHashMap<>(arguments);
        business.remove(ARGUMENT);
        business.remove(META_ARGUMENT);
        return business;
    }

    /**
     * Rejection wording for a keyless write: names the value the client must supply, where to put it
     * and what the retry semantics are — the client can comply from this message alone.
     */
    public static String missingKeyMessage() {
        return "写操作缺少幂等键：请在工具参数中加 " + ARGUMENT + "（一次操作一个值，建议用随机串如 UUID）；"
                + "同键重复调用返回首次结果、不会重复执行，换新键即为新的一次操作。"
                + "能自定义请求头的客户端也可改用 HTTP 头 Idempotency-Key。";
    }

    private static String headerValue(McpTransportContext ctx) {
        Object value = ctx == null ? null : ctx.get(McpContextKeys.IDEMPOTENCY_KEY);
        return textValue(value);
    }

    private static String textValue(Object value) {
        if (value instanceof String text && !text.isBlank()) {
            return text.trim();
        }
        return null;
    }

    private McpIdempotencyKey() {
    }
}
