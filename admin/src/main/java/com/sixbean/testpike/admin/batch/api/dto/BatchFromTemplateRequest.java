package com.sixbean.testpike.admin.batch.api.dto;


public record BatchFromTemplateRequest(long templateId, String name, long envId) {
}
