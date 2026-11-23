package com.sixbean.testpike.core.datafactory.api.dto;


public record DataFactoryInfo(long id, long projectId, long envId, String envName, int type,
                              String name, String content, String paramsJson, int status,
                              String createdByName, String createdAt) {
}
