package com.sixbean.testpike.aiservice.llm.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.sixbean.testpike.aiservice.llm.domain.LlmToolCall;

import java.util.List;

/**
 * <ul>
 * </ul>
 *
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatMessage(
        String role,
        @JsonIgnore String content,
        @JsonProperty("tool_call_id") String toolCallId,
        @JsonProperty("tool_calls") List<AssistantToolCall> toolCalls,
        @JsonIgnore List<ContentPart> parts) {

    
    public ChatMessage(String role, String content) {
        this(role, content, null, null, null);
    }

    
    public ChatMessage(String role, String content, String toolCallId,
                       List<AssistantToolCall> toolCalls) {
        this(role, content, toolCallId, toolCalls, null);
    }

    
    @JsonProperty("content")
    public Object contentValue() {
        return parts != null ? parts : content;
    }

    
    public String textContent() {
        if (parts == null) {
            return content == null ? "" : content;
        }
        StringBuilder text = new StringBuilder();
        for (ContentPart part : parts) {
            if (part.text() != null) {
                text.append(part.text());
            }
        }
        return text.toString();
    }

    
    public static ChatMessage userVision(String text, String imageUrl) {
        return new ChatMessage("user", null, null, null,
                List.of(ContentPart.text(text), ContentPart.imageUrl(imageUrl)));
    }

    
    public static ChatMessage tool(String toolCallId, String content) {
        return new ChatMessage("tool", content, toolCallId, null);
    }

    
    public static ChatMessage assistantWithToolCalls(String content, List<LlmToolCall> toolCalls) {
        List<AssistantToolCall> mirrored = toolCalls.stream()
                .map(c -> new AssistantToolCall(c.id(),
                        new AssistantToolFunction(c.name(), c.argumentsJson())))
                .toList();
        return new ChatMessage("assistant", content, null, mirrored);
    }
}
