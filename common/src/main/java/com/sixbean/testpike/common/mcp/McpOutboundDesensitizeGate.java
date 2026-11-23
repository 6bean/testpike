package com.sixbean.testpike.common.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sixbean.testpike.common.util.DesensitizeUtils;

/**
 * MCP outbound desensitization gate . Every body that leaves the
 * platform toward an external MCP server is prepared here, so the credential
 * and PII scrubbing rule lives in exactly one place and a new call site
 * cannot bypass it by accident. Lives in the common module because both
 * ai-service (agent tool bags) and core (MCP knowledge sync chain) send
 * outbound bodies — one implementation, zero copies.
 * <p>Behavior contract: with {@code enforce=true} the body runs through the
 * shared {@code DesensitizeUtils} masks (phone/email plus credential shapes)
 * and the payload is marked masked=1; with {@code enforce=false} (explicit
 * enterprise downgrade via the hot-updatable key
 * {@code ai.mcp.outbound-desensitize-enforce}) the body passes through
 * untouched and the payload honestly records masked=0 — the audit trail never
 * pretends scrubbing happened.
 * <p>Fail-closed: when masking itself fails with the gate on, the call must
 * not fall back to sending the raw body — the preparation throws so the
 * sender refuses the outbound (no escape hatch). The enforce flag parses the
 * same way: only an explicit "false" downgrades, missing or unreadable values
 * keep the gate on.
 */
public class McpOutboundDesensitizeGate {

    /** The one hot-updatable K8 key name, shared by every consumer face. */
    public static final String ENFORCE_KEY = "ai.mcp.outbound-desensitize-enforce";

    private final boolean enforce;

    public McpOutboundDesensitizeGate(boolean enforce) {
        this.enforce = enforce;
    }

    /**
     * Build the gate from the raw config value with the fail-closed rule in
     * exactly one place: null/blank/unreadable/anything-but-"false" keeps the
     * gate ON; only an explicit "false" (case and space tolerant) downgrades
     * to honest pass-through. Silent downgrade is banned by design.
     */
    public static McpOutboundDesensitizeGate fromRawValue(String rawValue) {
        return new McpOutboundDesensitizeGate(rawValue == null
                || !"false".equalsIgnoreCase(rawValue.trim()));
    }

    /**
     * Prepare one outbound body for sending to an external MCP server.
     *
     * @param outboundBody the body about to leave the platform (nullable)
     * @return the body to actually send plus the honest masked facet
     * @throws IllegalStateException gate on but masking failed — the raw body
     *         must never be sent as a degraded fallback
     */
    public McpOutboundPayload prepare(String outboundBody) {
        if (!enforce) {
            return new McpOutboundPayload(outboundBody, 0);
        }
        if (outboundBody == null || outboundBody.isEmpty()) {
            return new McpOutboundPayload(outboundBody, 1);
        }
        try {
            // Credential shapes first, then the phone/email text mask — both
            // from the shared DesensitizeUtils single source (pattern copies
            // at call sites are banned by the same rule this gate enforces).
            String scrubbed = DesensitizeUtils.maskText(
                    DesensitizeUtils.maskSecrets(outboundBody));
            return new McpOutboundPayload(scrubbed, 1);
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "MCP outbound desensitization failed, outbound call refused", e);
        }
    }

    /**
     * Prepare a tools/call arguments tree for one outbound call (core sync
     * chain face). Scrubbing walks the JSON tree instead of regexing its
     * serialized form: a value under a secret-faceted field name (the shared
     * {@code isSensitiveName} spectrum) collapses to {@code ***}, every other
     * text value runs the same credential+PII masks as {@link #prepare}.
     * Tree-wise scrubbing keeps the JSON valid — a text-level mask over a
     * serialized document can eat quotes and produce an unparsable body.
     *
     * @throws IllegalStateException gate on but the tree is missing or a mask
     *         threw — the raw tree must never be sent as a fallback
     */
    public JsonNode prepareNode(JsonNode arguments, ObjectMapper mapper) {
        if (arguments == null) {
            throw new IllegalStateException(
                    "MCP outbound desensitization failed, outbound call refused");
        }
        if (!enforce) {
            return arguments;
        }
        try {
            return scrub(arguments, null, mapper);
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "MCP outbound desensitization failed, outbound call refused", e);
        }
    }

    private JsonNode scrub(JsonNode node, String fieldName, ObjectMapper mapper) {
        if (node.isTextual()) {
            String scrubbed = DesensitizeUtils.isSensitiveName(fieldName)
                    ? "***"
                    : DesensitizeUtils.maskText(
                            DesensitizeUtils.maskSecrets(node.asText()));
            return mapper.getNodeFactory().textNode(scrubbed);
        }
        if (node.isObject()) {
            ObjectNode out = mapper.createObjectNode();
            node.fields().forEachRemaining(entry ->
                    out.set(entry.getKey(), scrub(entry.getValue(), entry.getKey(), mapper)));
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = mapper.createArrayNode();
            node.forEach(item -> out.add(scrub(item, fieldName, mapper)));
            return out;
        }
        return node.deepCopy();
    }

    public boolean enforce() {
        return enforce;
    }
}
