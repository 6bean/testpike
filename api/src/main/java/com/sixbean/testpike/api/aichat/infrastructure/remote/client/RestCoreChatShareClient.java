package com.sixbean.testpike.api.aichat.infrastructure.remote.client;

import com.sixbean.testpike.api.aichat.api.dto.ChatShareSnapshotMirrorView;
import com.sixbean.testpike.common.client.RestClientInvoker;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * RestClient implementation of the dialog-share bridge (AO batch P2
 * ; mirrors RestCoreCiBatchClient's posture).
 * <p>Replies unwrap through {@link RestClientInvoker}: core's business
 * refusals (400 garbage token, the one 404 sentence for
 * unknown/expired/revoked) surface as ServiceException and the api
 * GlobalExceptionHandler maps them to HTTP status — the public door adds no
 * second sentence of its own.
 */
@Component
public class RestCoreChatShareClient implements CoreChatShareClient {

    private static final ParameterizedTypeReference<Response<ChatShareSnapshotMirrorView>>
            RESP_SHARE = new ParameterizedTypeReference<>() {
            };

    private final RestClient rest;

    public RestCoreChatShareClient(
            @Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public ChatShareSnapshotMirrorView openShare(String shareToken) {
        return RestClientInvoker.call(rest, HttpMethod.GET,
                "/internal/ai-chat/shares/" + shareToken, null, RESP_SHARE, null);
    }
}
