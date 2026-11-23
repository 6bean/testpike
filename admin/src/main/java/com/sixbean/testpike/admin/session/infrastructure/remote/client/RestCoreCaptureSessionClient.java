package com.sixbean.testpike.admin.session.infrastructure.remote.client;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.session.api.dto.CaptureCandidateView;
import com.sixbean.testpike.admin.session.api.dto.CaptureSessionStartRequest;
import com.sixbean.testpike.admin.session.api.dto.CaptureSessionView;
import com.sixbean.testpike.common.client.RestClientInvoker;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * RestClient implementation of the capture-session bridge
 * (RestCoreLoginStateClient idiom — X-Operator-Id pass-through, core wording
 * propagated verbatim, no second validation here). View and core record share
 * field names for direct mapping; the transient session id travels as text.
 */
@Component
public class RestCoreCaptureSessionClient implements CoreCaptureSessionClient {

    private static final String BASE = "/internal/session-capture-sessions";

    private final RestClient rest;

    public RestCoreCaptureSessionClient(@Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public String openSession(CaptureSessionStartRequest request) {
        return RestClientInvoker.call(rest, HttpMethod.POST,
                BASE + "?projectId=" + request.projectId(), request,
                new ParameterizedTypeReference<Response<String>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public CaptureSessionView findSession(String sessionId) {
        return RestClientInvoker.call(rest, HttpMethod.GET, BASE + "/" + sessionId, null,
                new ParameterizedTypeReference<Response<CaptureSessionView>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public void abandonSession(String sessionId) {
        RestClientInvoker.call(rest, HttpMethod.DELETE, BASE + "/" + sessionId, null,
                new ParameterizedTypeReference<Response<Void>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public java.util.List<CaptureCandidateView> findCandidates(String sessionId) {
        return RestClientInvoker.call(rest, HttpMethod.GET, BASE + "/" + sessionId + "/candidates",
                null,
                new ParameterizedTypeReference<Response<java.util.List<CaptureCandidateView>>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public int commitSession(String sessionId, java.util.List<String> selectedNames) {
        Integer accepted = RestClientInvoker.call(rest, HttpMethod.POST,
                BASE + "/" + sessionId + "/commit", selectedNames,
                new ParameterizedTypeReference<Response<Integer>>() {
                }, UserContext.currentUserId());
        return accepted == null ? 0 : accepted;
    }
}
