package com.sixbean.testpike.admin.apitest.api.dto;

/**
 *
 * @param mockStale derived-mock twin badge (diff hit + openapi-sourced stub
 * exists = true, cleared on edit; always a subset of
 * contractStale — mirrors the core wire shape)
 */
public record RequestView(long id, long collectionId, String name, String method,
                          String urlPath, String headersJson, String bodyText,
                          String authJson, String assertionsJson, Long promotedCaseId,
                          boolean contractStale, boolean mockStale,
                          String preconditionJson, String loopJson, String protocol) {
}
