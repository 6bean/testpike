package com.sixbean.testpike.core.prompt.api.dto;


public record PromptTemplateItem(Long id, String name, String domain, Integer version,
                                 String content, Integer status, Long createdBy,
                                 Long rollbackTo, String createdAt) {
}
