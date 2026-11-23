package com.sixbean.testpike.aiservice.llm.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * The {@code json_schema} payload of a response-format block: named output
 * contract, strict flag (vendors that ignore it degrade to advisory decoding)
 * and the schema tree itself.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JsonSchemaSpec(String name, Boolean strict, JsonNode schema) {
}
