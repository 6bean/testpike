package com.sixbean.testpike.core.datafactory.api.dto;

/**
 *
 * @param id run id
 */
public record DataFactoryRunInfo(long id, long factoryId, int status, String resultSummary,
                                 String createdByName, String createdAt) {
}
