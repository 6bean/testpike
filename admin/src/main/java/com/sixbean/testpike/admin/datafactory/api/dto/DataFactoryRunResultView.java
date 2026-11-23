package com.sixbean.testpike.admin.datafactory.api.dto;


public record DataFactoryRunResultView(long factoryId, int status, int createdCount, int poolCount,
                                       String message) {
}
