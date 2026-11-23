package com.sixbean.testpike.core.apitest.api.dto;


public record StepAliasInput(Long projectId, String aliasName, String kind,
                             String target, String ownerRef) {
}
