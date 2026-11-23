package com.sixbean.testpike.core.apitest.api.dto;

/**
 *
 * @param kind path / step
 */
public record StepAliasInfo(long id, long projectId, String aliasName, String kind,
                            String target, String ownerRef) {
}
