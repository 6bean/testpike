package com.sixbean.testpike.admin.prompt.api.dto;


public record PromptTemplateView(Long id, String name, String domain, Integer version,
                                 String content, Integer status, Long createdBy,
                                 Long rollbackTo, String createdAt) {
}
