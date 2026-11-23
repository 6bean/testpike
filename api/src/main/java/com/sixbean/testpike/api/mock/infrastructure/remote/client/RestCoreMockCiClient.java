package com.sixbean.testpike.api.mock.infrastructure.remote.client;

import com.sixbean.testpike.api.mock.api.dto.MockCiExportView;
import com.sixbean.testpike.api.mock.api.dto.MockCiSpaceInput;
import com.sixbean.testpike.common.client.RestClientInvoker;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * RestClient implementation of the mock-CI bridge (mirrors RestCoreCiBatchClient
 * posture: shared RestClientInvoker so response unwrapping, error-code
 * passthrough and X-Operator-Id forwarding are never re-implemented — red line 6).
 */
@Component
public class RestCoreMockCiClient implements CoreMockCiClient {

    private static final String BASE = "/internal/mock";

    private static final ParameterizedTypeReference<Response<Long>> RESP_LONG =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<Response<MockCiExportView>> RESP_EXPORT =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient rest;

    public RestCoreMockCiClient(
            @Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public long createSpace(long projectId, Integer ttlHours, MockCiSpaceInput input,
                            long operatorId) {
        String uri = BASE + "/space/create?projectId=" + projectId
                + (ttlHours == null ? "" : "&ttlHours=" + ttlHours);
        return RestClientInvoker.call(rest, HttpMethod.POST, uri, input, RESP_LONG, operatorId);
    }

    @Override
    public MockCiExportView exportSpace(long spaceId, String format) {
        return RestClientInvoker.call(rest, HttpMethod.GET,
                BASE + "/space/export?spaceId=" + spaceId + "&format=" + format,
                null, RESP_EXPORT, null);
    }

    @Override
    public long countHits(long spaceId, String since, Boolean matched) {
        StringBuilder uri = new StringBuilder(BASE + "/hit/count?spaceId=").append(spaceId);
        // Query values are built by explicit concat (URI.create inside
        // RestClientInvoker keeps them verbatim — no double-encoding trap).
        if (since != null && !since.isBlank()) {
            uri.append("&since=").append(since.trim());
        }
        if (matched != null) {
            uri.append("&matched=").append(matched);
        }
        return RestClientInvoker.call(rest, HttpMethod.GET, uri.toString(), null, RESP_LONG, null);
    }
}
