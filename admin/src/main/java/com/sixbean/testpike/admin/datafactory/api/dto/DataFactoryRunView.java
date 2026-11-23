package com.sixbean.testpike.admin.datafactory.api.dto;

/**
 *
 * @param id run id
 */
public record DataFactoryRunView(long id, long factoryId, int status, String resultSummary,
                                 String createdByName, String createdAt) {
}
