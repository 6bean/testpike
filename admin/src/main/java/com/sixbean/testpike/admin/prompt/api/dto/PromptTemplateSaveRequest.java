package com.sixbean.testpike.admin.prompt.api.dto;


public record PromptTemplateSaveRequest(String name, String domain, String content,
                                        Long createdBy) {

    
    public PromptTemplateSaveRequest withCreatedBy(long operatorId) {
        return new PromptTemplateSaveRequest(name, domain, content, operatorId);
    }
}
