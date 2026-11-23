package com.sixbean.testpike.aiservice.llm.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;


@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatToolDef(String type, ChatToolFunction function) {

    
    public static final String TYPE_FUNCTION = "function";

    public ChatToolDef(ChatToolFunction function) {
        this(TYPE_FUNCTION, function);
    }
}
