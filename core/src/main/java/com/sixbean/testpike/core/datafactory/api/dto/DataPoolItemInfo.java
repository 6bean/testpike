package com.sixbean.testpike.core.datafactory.api.dto;


public record DataPoolItemInfo(long id, long projectId, long envId, String envName, String type,
                               String value, String name, int status, String statusLabel,
                               String createdAt, Long createdBy) {
}
