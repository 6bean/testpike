package com.sixbean.testpike.aiservice.llm.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * OpenAI-compatible {@code stream_options} body field (AO batch dialog kernel):
 * asks the vendor to append a final chunk carrying usage for the streamed
 * answer, so billing attribution works identically to non-streaming calls.
 * Serialized only when the request itself is streaming.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StreamOptions(@JsonProperty("include_usage") Boolean includeUsage) {

    public static StreamOptions withUsage() {
        return new StreamOptions(Boolean.TRUE);
    }
}
