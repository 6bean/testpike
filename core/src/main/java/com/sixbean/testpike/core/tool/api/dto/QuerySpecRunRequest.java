package com.sixbean.testpike.core.tool.api.dto;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Request body of the internal aggregate-query door (AO batch P3 segment
 * 2). The spec travels as an unparsed {@link JsonNode} on purpose: the
 * interpreter re-reads the raw object, so keys smuggled outside the DSL shape
 * are refused fail-closed instead of being dropped silently by a record
 * binding (boundary #2 of the QuerySpec contract).
 *
 * @param spec the model-produced QuerySpec JSON object, verbatim
 */
public record QuerySpecRunRequest(JsonNode spec) {
}
