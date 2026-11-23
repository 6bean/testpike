package com.sixbean.testpike.admin.project.api.dto;


public record TemplateEnvItemRequest(String name, Integer envType, String baseUrl,
                                     String endTypes, String browsers) {
}
