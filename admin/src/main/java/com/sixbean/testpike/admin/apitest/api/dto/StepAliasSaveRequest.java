package com.sixbean.testpike.admin.apitest.api.dto;


public record StepAliasSaveRequest(Long projectId, String aliasName, String kind,
                                   String target, String ownerRef) {
}
