package com.sixbean.testpike.core.datafactory.api.dto;


public record DataFactoryCreateInput(long projectId, long envId, int type, String name,
                                     String content, String paramsJson) {
}
