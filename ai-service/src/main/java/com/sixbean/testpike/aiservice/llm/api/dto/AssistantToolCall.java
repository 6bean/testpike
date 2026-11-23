package com.sixbean.testpike.aiservice.llm.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;


@JsonInclude(JsonInclude.Include.NON_NULL)
public record AssistantToolCall(
        String id,
        String type,
        @JsonProperty("function") AssistantToolFunction function) {

    
    public static final String TYPE_FUNCTION = "function";

    public AssistantToolCall(String id, AssistantToolFunction function) {
        this(id, TYPE_FUNCTION, function);
    }
}
