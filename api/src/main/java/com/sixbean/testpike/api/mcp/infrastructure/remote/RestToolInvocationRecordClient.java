package com.sixbean.testpike.api.mcp.infrastructure.remote;

import com.sixbean.testpike.common.client.RestClientInvoker;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestClient;

import java.util.List;


public class RestToolInvocationRecordClient implements ToolInvocationRecordClient {

    private static final ParameterizedTypeReference<Response<Integer>> RESP_COUNT =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient rest;

    public RestToolInvocationRecordClient(String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public void record(List<McpInvocationRemoteInput> invocations) {
        if (invocations == null || invocations.isEmpty()) {
            return;
        }
        RestClientInvoker.call(rest, HttpMethod.POST,
                "/internal/mcp-servers/invocations", invocations, RESP_COUNT, null);
    }
}
