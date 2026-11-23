package com.sixbean.testpike.admin.apitest.api.dto;

/** Admin mirror of core FlowTemplateSaveInput (save-as-template request). */
public record FlowTemplateSaveRequest(Long collectionId, String name, String description) {
}
