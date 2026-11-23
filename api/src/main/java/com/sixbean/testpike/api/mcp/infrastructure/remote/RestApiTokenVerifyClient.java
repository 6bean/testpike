package com.sixbean.testpike.api.mcp.infrastructure.remote;

import com.sixbean.testpike.common.client.RestClientInvoker;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;


@Component
public class RestApiTokenVerifyClient implements ApiTokenVerifyClient {

    private static final ParameterizedTypeReference<Response<ApiTokenVerifyPayload>> RESP_VERIFY =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient rest;

    public RestApiTokenVerifyClient(
            @Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public McpVerifyOutcome verify(String plaintext) {
        ApiTokenVerifyPayload payload = RestClientInvoker.call(rest, HttpMethod.POST,
                "/internal/api-tokens/verify", Map.of("plaintext", plaintext), RESP_VERIFY, null);
        if (payload == null) {
            return McpVerifyOutcome.invalid("Token 无效或认证服务未返回结果");
        }
        if (payload.ok()) {
            return McpVerifyOutcome.ok(payload.userId(), payload.tokenId(),
                    payload.scopes() == null ? List.of() : payload.scopes(),
                    payload.projectScope() == null ? List.of() : payload.projectScope());
        }
        String message = payload.message() == null ? "Token 无效" : payload.message();
        String status = payload.status() == null ? "INVALID" : payload.status();
        return switch (status) {
            case "REVOKED" -> McpVerifyOutcome.revoked(message);
            case "EXPIRED" -> McpVerifyOutcome.expired(message);
            default -> McpVerifyOutcome.invalid(message);
        };
    }
}
