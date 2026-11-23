package com.sixbean.testpike.aiservice.llm.domain;


public record LlmToolCall(String id, String name, String argumentsJson) {
}
