package com.sixbean.testpike.admin.journey.infrastructure.remote.client;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.journey.api.dto.AddJourneySegmentRequest;
import com.sixbean.testpike.admin.journey.api.dto.CreateJourneyRequest;
import com.sixbean.testpike.admin.journey.api.dto.JourneyPageView;
import com.sixbean.testpike.admin.journey.api.dto.JourneyRunView;
import com.sixbean.testpike.admin.journey.api.dto.JourneySegmentView;
import com.sixbean.testpike.admin.journey.api.dto.JourneyView;
import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.client.RestClientInvoker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * RestClient implementation of {@link CoreJourneyClient} (admin → core HTTP bridge).
 * <p>X-Operator-Id carries the current user so core keeps its audit chain closed and the
 * sign-off guard sees the real end user; error codes (400/403/404/409 semantics) pass
 * through unchanged. Shared call logic lives in {@link RestClientInvoker} — this class
 * only maps endpoints.
 */
@Component
public class RestCoreJourneyClient implements CoreJourneyClient {

    private final RestClient rest;

    public RestCoreJourneyClient(@Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public long createJourney(CreateJourneyRequest request) {
        Long id = call(HttpMethod.POST, "/internal/journeys", request,
                new ParameterizedTypeReference<Response<Long>>() { });
        return id == null ? 0L : id;
    }

    @Override
    public JourneySegmentView addSegment(long journeyId, AddJourneySegmentRequest request) {
        return call(HttpMethod.POST, "/internal/journeys/" + journeyId + "/segments", request,
                new ParameterizedTypeReference<Response<JourneySegmentView>>() { });
    }

    @Override
    public JourneyView getJourney(long journeyId) {
        return call(HttpMethod.GET, "/internal/journeys/" + journeyId, null,
                new ParameterizedTypeReference<Response<JourneyView>>() { });
    }

    @Override
    public JourneyPageView pageJourneys(long programId, int page, int pageSize, String keyword) {
        StringBuilder uri = new StringBuilder("/internal/journeys/page?programId=")
                .append(programId).append("&page=").append(page)
                .append("&pageSize=").append(pageSize);
        if (keyword != null && !keyword.isBlank()) {
            uri.append("&keyword=").append(encode(keyword));
        }
        return call(HttpMethod.GET, uri.toString(), null,
                new ParameterizedTypeReference<Response<JourneyPageView>>() { });
    }

    @Override
    public JourneyRunView startRun(long journeyId) {
        return call(HttpMethod.POST, "/internal/journeys/" + journeyId + "/runs", null,
                new ParameterizedTypeReference<Response<JourneyRunView>>() { });
    }

    @Override
    public JourneyRunView getRun(long runId) {
        return call(HttpMethod.GET, "/internal/journeys/runs/" + runId, null,
                new ParameterizedTypeReference<Response<JourneyRunView>>() { });
    }

    @Override
    public JourneyRunView signOff(long runId) {
        return call(HttpMethod.POST, "/internal/journeys/runs/" + runId + "/signoff", null,
                new ParameterizedTypeReference<Response<JourneyRunView>>() { });
    }

    /** Uniform invocation (operator id forwarded for X-Operator-Id; shared logic in RestClientInvoker). */
    private <T> T call(HttpMethod method, String uri, Object body,
                       ParameterizedTypeReference<Response<T>> type) {
        return RestClientInvoker.call(rest, method, uri, body, type, UserContext.currentUserId());
    }

    /** UTF-8 query encoding, pre-encoded once here — RestClientInvoker keeps our bytes (double-encode guard). */
    private String encode(String value) {
        return java.net.URLEncoder.encode(value.trim(), java.nio.charset.StandardCharsets.UTF_8);
    }
}
