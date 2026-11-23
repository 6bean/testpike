package com.sixbean.testpike.aiservice.gen.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatMessage;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmEndpointConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * contract: structured-output attachment is dual-form. Capable + gate
 * on → the wire request carries a json_schema response_format block; anything
 * else (gate off, row opt-out, column-less legacy with gate explicitly off) →
 * the request serializes byte-identically to the legacy shape, and the refusal
 * degrade path strips the block again without touching anything else.
 */
class GenStructuredOutputTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static LlmEndpointConfig endpoint(Boolean structuredOutput) {
        return new LlmEndpointConfig("openai", "gpt-test", null, "http://127.0.0.1:1/v1",
                LlmEndpointConfig.SOURCE_CONFIG, null, null, null, null, structuredOutput);
    }

    private static ChatCompletionRequest baseRequest() {
        return new ChatCompletionRequest("gpt-test", List.of(
                new ChatMessage("system", "sys"),
                new ChatMessage("user", "prompt")), null, null);
    }

    @Test
    void attachesJsonSchemaBlockWhenGateOnAndEndpointCapable() throws Exception {
        ChatCompletionRequest shaped = GenStructuredOutput.decorate(baseRequest(),
                endpoint(null), true, GenStructuredOutput.CASE_CONTRACT);
        JsonNode wire = mapper.readTree(mapper.writeValueAsString(shaped));
        JsonNode format = wire.path("response_format");
        assertEquals("json_schema", format.path("type").asText());
        assertEquals(GenStructuredOutput.CASE_CONTRACT,
                format.path("json_schema").path("name").asText());
        assertTrue(format.path("json_schema").path("strict").asBoolean());
        assertEquals("object", format.path("json_schema").path("schema").path("type").asText());
    }

    @Test
    void gateOffKeepsLegacyShapeByteIdentical() throws Exception {
        String legacyWire = mapper.writeValueAsString(baseRequest());
        ChatCompletionRequest base = baseRequest();
        assertSame(base, GenStructuredOutput.decorate(base,
                endpoint(Boolean.TRUE), false, GenStructuredOutput.CASE_CONTRACT));
        ChatCompletionRequest shaped = GenStructuredOutput.decorate(baseRequest(),
                endpoint(Boolean.TRUE), false, GenStructuredOutput.CASE_CONTRACT);
        assertEquals(legacyWire, mapper.writeValueAsString(shaped));
        assertNull(shaped.responseFormat());
    }

    @Test
    void rowOptOutKeepsLegacyShapeByteIdentical() throws Exception {
        String legacyWire = mapper.writeValueAsString(baseRequest());
        ChatCompletionRequest shaped = GenStructuredOutput.decorate(baseRequest(),
                endpoint(Boolean.FALSE), true, GenStructuredOutput.SCRIPT_CONTRACT);
        assertEquals(legacyWire, mapper.writeValueAsString(shaped));
    }

    @Test
    void refusalDegradeStripsOnlyTheFormatBlock() throws Exception {
        ChatCompletionRequest shaped = GenStructuredOutput.decorate(baseRequest(),
                endpoint(null), true, GenStructuredOutput.SCRIPT_CONTRACT);
        ChatCompletionRequest stripped = GenStructuredOutput.strip(shaped);
        assertNotNull(shaped.responseFormat());
        assertNull(stripped.responseFormat());
        assertEquals(mapper.writeValueAsString(baseRequest()),
                mapper.writeValueAsString(stripped));
    }

    /**
     * Window w11 attribution: while the schema admits an empty array,
     * constrained decoding re-rolls the schema-valid empty {@code steps}
     * every deliberation-ring round, and ActionJsonValidator rejects an
     * empty steps array, so the ring exhausts without progress.
     * The non-empty bound therefore belongs in the shape contract.
     * The same-family check on {@code setup} keeps it permissive on
     * purpose: the 04 contract treats setup as optional (login/data
     * preparation only) and no validator requires it.
     */
    @Test
    void stepArraysAreNonEmptyInBothContractSchemas() {
        JsonNode caseSchema = GenStructuredOutput.schemaFor(GenStructuredOutput.CASE_CONTRACT);
        JsonNode scriptSchema = GenStructuredOutput.schemaFor(GenStructuredOutput.SCRIPT_CONTRACT);
        assertEquals(1, caseSchema.path("properties").path("steps").path("minItems").asInt(-1));
        assertEquals(1, scriptSchema.path("properties").path("steps").path("minItems").asInt(-1));
        assertFalse(scriptSchema.path("properties").path("setup").has("minItems"));
    }

    @Test
    void bothContractSchemasAreParseableObjects() {
        JsonNode caseSchema = GenStructuredOutput.schemaFor(GenStructuredOutput.CASE_CONTRACT);
        JsonNode scriptSchema = GenStructuredOutput.schemaFor(GenStructuredOutput.SCRIPT_CONTRACT);
        assertNotNull(caseSchema);
        assertNotNull(scriptSchema);
        assertEquals("object", caseSchema.path("type").asText());
        assertEquals("object", scriptSchema.path("type").asText());
        assertTrue(caseSchema.path("required").toString().contains("title"));
        assertTrue(scriptSchema.path("required").toString().contains("steps"));
    }

    /**
 * S4 window attribution (identical rejection fingerprint three runs
 * in a row across both regen L3 windows, eight model calls): the ring
 * feedback named the missing pieces every revision round, yet the script
 * schema stayed looser than ActionJsonValidator where the validator is
 * strict. The validator always demands {@code caseId} and {@code env}
 * (04 rule 2) and a url on every goto step (04 §2.1: top-level {@code url},
 * {@code target.url} or {@code target.href}); the schema required neither
 * {@code env} nor {@code caseId} and declared no step-level {@code url}
 * property, so constrained decoding satisfies the schema — not the
 * validator — and re-rolls the same rejected shape every round. Feedback
 * cannot outvote the schema; the shape the validator needs therefore
 * belongs in the shape contract. Same family as the w11 lesson above.
 */
    @Test
    void scriptSchemaCoversCaseIdEnvAndStepUrlThatValidatorRequires() {
        JsonNode scriptSchema = GenStructuredOutput.schemaFor(GenStructuredOutput.SCRIPT_CONTRACT);
        String required = scriptSchema.path("required").toString();
        assertTrue(required.contains("caseId"),
                "validator rejects a blank caseId, so the schema must require it: " + required);
        assertTrue(required.contains("env"),
                "validator rejects a missing top-level env, so the schema must require it: "
                        + required);
        JsonNode stepProps = scriptSchema.path("properties").path("steps")
                .path("items").path("properties");
        assertTrue(stepProps.has("url"),
                "goto carries its url at the step level (04 §2.1), so the schema must allow it: "
                        + stepProps);
    }
}
