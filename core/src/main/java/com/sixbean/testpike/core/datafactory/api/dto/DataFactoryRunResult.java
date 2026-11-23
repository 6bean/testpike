package com.sixbean.testpike.core.datafactory.api.dto;


public record DataFactoryRunResult(long factoryId, int status, int createdCount, int poolCount,
                                   String message) {
}
