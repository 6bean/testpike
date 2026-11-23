package com.sixbean.testpike.admin.datafactory.api.dto;


public record DataPoolItemView(long id, long projectId, long envId, String envName, String type,
                               String value, String name, int status, String statusLabel,
                               String createdAt, Long createdBy) {
}
