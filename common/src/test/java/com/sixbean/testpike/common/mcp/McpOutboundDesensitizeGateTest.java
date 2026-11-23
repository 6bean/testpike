package com.sixbean.testpike.common.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavior contract of the MCP outbound desensitization gate :
 * gate on scrubs credential and PII shapes and marks masked=1; the explicit
 * enterprise downgrade passes the body through and records masked=0; a
 * masking failure with the gate on must refuse the outbound instead of
 * falling back to the raw body; the enforce flag is parsed from the raw K8
 * value fail-closed (only an explicit "false" downgrades).
 */
class McpOutboundDesensitizeGateTest {

    @Test
    void gateOnScrubsCredentialAndPhoneShapes() {
        McpOutboundDesensitizeGate gate = new McpOutboundDesensitizeGate(true);
        String body = "查询 13812345678 的档案，api_key: supersecretvalue123";
        McpOutboundPayload payload = gate.prepare(body);
        assertEquals(1, payload.masked());
        assertFalse(payload.body().contains("13812345678"));
        assertFalse(payload.body().contains("supersecretvalue123"));
    }

    @Test
    void gateOffPassesThroughAndRecordsHonestly() {
        McpOutboundDesensitizeGate gate = new McpOutboundDesensitizeGate(false);
        McpOutboundPayload payload = gate.prepare("raw 13812345678 api_key: keepme");
        assertEquals(0, payload.masked());
        assertEquals("raw 13812345678 api_key: keepme", payload.body());
    }

    @Test
    void emptyBodyStillMarkedMaskedWhenGateOn() {
        McpOutboundDesensitizeGate gate = new McpOutboundDesensitizeGate(true);
        McpOutboundPayload payload = gate.prepare("");
        assertEquals(1, payload.masked());
        assertEquals("", payload.body());
    }

    @Test
    void enforceKeyIsTheSingleHotUpdatableKeyName() {
        // The key literal must live exactly once so ai/core/seed cannot drift
        // to different names (the very bypass the coverage gate hunts).
        assertEquals("ai.mcp.outbound-desensitize-enforce",
                McpOutboundDesensitizeGate.ENFORCE_KEY);
    }

    @Test
    void rawValueParsesFailClosed() {
        // Only an explicit "false" downgrades; missing/null/unreadable keeps on.
        assertFalse(McpOutboundDesensitizeGate.fromRawValue("false").enforce());
        assertFalse(McpOutboundDesensitizeGate.fromRawValue("FALSE ").enforce());
        assertTrue(McpOutboundDesensitizeGate.fromRawValue(null).enforce());
        assertTrue(McpOutboundDesensitizeGate.fromRawValue("true").enforce());
        assertTrue(McpOutboundDesensitizeGate.fromRawValue("garbage").enforce());
    }

    @Test
    void prepareNodeScrubbedInsideJsonTreeKeepsObjectShape() {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode args = mapper.valueToTree(java.util.Map.of(
                "q", "客户手机号 13812345678",
                "token", "sk-abcdefghijklmnop"));
        McpOutboundDesensitizeGate gate = new McpOutboundDesensitizeGate(true);
        JsonNode scrubbed = gate.prepareNode(args, mapper);
        assertTrue(scrubbed.isObject());
        assertFalse(scrubbed.get("q").asText().contains("13812345678"));
        assertFalse(scrubbed.toString().contains("sk-abcdefghijklmnop"));
    }

    @Test
    void prepareNodePassesThroughWhenGateOff() {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode args = mapper.valueToTree(java.util.Map.of("q", "raw 13812345678"));
        McpOutboundDesensitizeGate gate = new McpOutboundDesensitizeGate(false);
        JsonNode same = gate.prepareNode(args, mapper);
        assertEquals("raw 13812345678", same.get("q").asText());
    }

    @Test
    void prepareNodeRefusesMissingArgumentTreeWhenGateOn() {
        // Fail-closed: a preparation that cannot run its masks must throw
        // (refuse the outbound), never return a raw passthrough.
        ObjectMapper mapper = new ObjectMapper();
        McpOutboundDesensitizeGate gate = new McpOutboundDesensitizeGate(true);
        assertThrows(RuntimeException.class, () -> gate.prepareNode(null, mapper));
    }
}
