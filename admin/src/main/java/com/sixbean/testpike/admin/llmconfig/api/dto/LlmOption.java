package com.sixbean.testpike.admin.llmconfig.api.dto;


public record LlmOption(long id, String name, String provider, String modelName, String baseUrl) {
}
