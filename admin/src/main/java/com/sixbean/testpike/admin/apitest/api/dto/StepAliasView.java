package com.sixbean.testpike.admin.apitest.api.dto;


public record StepAliasView(long id, long projectId, String aliasName, String kind,
                            String target, String ownerRef) {
}
