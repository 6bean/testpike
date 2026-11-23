package com.sixbean.testpike.admin.tags.api.dto;


public record CreateTagRequest(long projectId, String name, Integer type) {
}
