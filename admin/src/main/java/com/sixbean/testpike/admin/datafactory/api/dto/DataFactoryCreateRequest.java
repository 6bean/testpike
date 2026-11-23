package com.sixbean.testpike.admin.datafactory.api.dto;


public record DataFactoryCreateRequest(long projectId, long envId, int type, String name,
                                       String content, String paramsJson) {
}
