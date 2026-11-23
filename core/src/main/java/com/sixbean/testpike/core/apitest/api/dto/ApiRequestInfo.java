package com.sixbean.testpike.core.apitest.api.dto;

/**
 *
 * @param mockStale derived-mock twin badge: true when the same diff hit has
 * contract-derived (source=openapi) stubs in this project;
 * cleared on edit like contractStale; always a subset scope
 */
public record ApiRequestInfo(long id, long collectionId, String name, String method,
                             String urlPath, String headersJson, String bodyText,
                             String authJson, String assertionsJson, Long promotedCaseId,
                             boolean contractStale, boolean mockStale,
                             String preconditionJson, String loopJson, String protocol) {
}
