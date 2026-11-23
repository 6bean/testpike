package com.sixbean.testpike.admin.datafactory.api.dto;


public record DataFactoryRecordView(long id, long projectId, long envId, String envName, int type,
                                    String name, String content, String paramsJson, int status,
                                    String createdByName, String createdAt) {
}
