package com.sixbean.testpike.api.ci.api.dto;


public record CiCreateFromTemplateInput(long templateId, String name, long envId) {
}
