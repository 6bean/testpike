package com.sixbean.testpike.admin.apitest.api.dto;


public record RequestSaveRequest(Long collectionId, String name, String method, String urlPath,
                                 String headersJson, String bodyText, String authJson,
                                 String assertionsJson, String preconditionJson, String loopJson,
                                 String protocol) {
}
