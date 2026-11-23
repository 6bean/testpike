package com.sixbean.testpike.core.apitest.api.dto;

/**
 *
 * @param preconditionJson orchestration precondition object JSON text
 * ({field,op,expect,...}; null/blank/{} = always run; domain
 * single source common ApiOrchestrationContract, save-gated)
 * @param loopJson orchestration loop object JSON text
 * ({mode,times|until,maxTimes,delayMs}; null/blank/{} =
 * straight-line run; same contract gate as preconditionJson)
 * @param protocol transport discriminator (http | ws; null/blank =
 * http default; vocabulary single source common ApiWsContract,
 * unknown values refused at the save gate)
 */
public record RequestSaveInput(Long collectionId, String name, String method, String urlPath,
                               String headersJson, String bodyText, String authJson,
                               String assertionsJson, String preconditionJson, String loopJson,
                               String protocol) {

    /** Eight-argument legacy form: no precondition, no loop, default transport. */
    public RequestSaveInput(Long collectionId, String name, String method, String urlPath,
                            String headersJson, String bodyText, String authJson,
                            String assertionsJson) {
        this(collectionId, name, method, urlPath, headersJson, bodyText, authJson,
                assertionsJson, null, null, null);
    }

    /** Ten-argument form : orchestration rows, default transport. */
    public RequestSaveInput(Long collectionId, String name, String method, String urlPath,
                            String headersJson, String bodyText, String authJson,
                            String assertionsJson, String preconditionJson, String loopJson) {
        this(collectionId, name, method, urlPath, headersJson, bodyText, authJson,
                assertionsJson, preconditionJson, loopJson, null);
    }
}
