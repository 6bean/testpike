package com.sixbean.testpike.aiservice.llm.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * OpenAI-compatible {@code response_format} block .
 * <p>When present on a request it constrains provider decoding to the carried
 * JSON Schema, so format-level re-rolls trend to zero. Null on every legacy
 * request (class-level NON_NULL keeps the serialized shape byte-identical).
 * <p>Two shipped shapes: {@code json_object} (loosest) and {@code json_schema}
 * with a schema tree; capability detection and honest fallback to the legacy
 * parse-and-revise path live at the consumption point, never here.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatResponseFormat(
        String type,
        @JsonProperty("json_schema") JsonSchemaSpec jsonSchema) {

    /** Standard type codes carried on the wire. */
    public static final String TYPE_JSON_OBJECT = "json_object";
    public static final String TYPE_JSON_SCHEMA = "json_schema";

    /** Loose JSON-object format (provider guarantees valid JSON only). */
    public static ChatResponseFormat jsonObject() {
        return new ChatResponseFormat(TYPE_JSON_OBJECT, null);
    }

    /** Schema-constrained format for a named output contract. */
    public static ChatResponseFormat jsonSchema(String name, boolean strict, JsonNode schema) {
        return new ChatResponseFormat(TYPE_JSON_SCHEMA, new JsonSchemaSpec(name, strict, schema));
    }
}
