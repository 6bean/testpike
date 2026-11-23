package com.sixbean.testpike.admin.credential.infrastructure.remote.client;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.credential.api.dto.CredentialSaveRequest;
import com.sixbean.testpike.admin.credential.api.dto.CredentialView;
import com.sixbean.testpike.common.client.RestClientInvoker;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;


@Component
public class RestCoreCredentialClient implements CoreCredentialClient {

    private static final String BASE = "/internal/credentials";

    private final RestClient rest;

    public RestCoreCredentialClient(@Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public long createCredential(long projectId, CredentialSaveRequest request) {
        Long id = call(HttpMethod.POST, BASE + "?projectId=" + projectId, request,
                new ParameterizedTypeReference<Response<Long>>() {
                });
        return id == null ? 0L : id;
    }

    @Override
    public void updateCredential(long id, CredentialSaveRequest request) {
        call(HttpMethod.PUT, BASE + "/" + id, request,
                new ParameterizedTypeReference<Response<Void>>() {
                });
    }

    @Override
    public CredentialView findCredential(long id) {
        return call(HttpMethod.GET, BASE + "/" + id, null,
                new ParameterizedTypeReference<Response<CredentialView>>() {
                });
    }

    @Override
    public List<CredentialView> listCredentials(long projectId, Boolean enabledOnly) {
        StringBuilder uri = new StringBuilder(BASE + "?projectId=").append(projectId);
        if (enabledOnly != null) {
            uri.append("&enabledOnly=").append(enabledOnly);
        }
        return call(HttpMethod.GET, uri.toString(), null,
                new ParameterizedTypeReference<Response<List<CredentialView>>>() {
                });
    }

    @Override
    public void setCredentialEnabled(long id, boolean enabled) {
        call(HttpMethod.PUT, BASE + "/" + id + "/enabled?enabled=" + enabled, null,
                new ParameterizedTypeReference<Response<Void>>() {
                });
    }

    @Override
    public void deleteCredential(long id) {
        call(HttpMethod.DELETE, BASE + "/" + id, null,
                new ParameterizedTypeReference<Response<Void>>() {
                });
    }

    private <T> T call(HttpMethod method, String uri, Object body,
                       ParameterizedTypeReference<Response<T>> type) {
        return RestClientInvoker.call(rest, method, uri, body, type, UserContext.currentUserId());
    }

    @Override
    public com.sixbean.testpike.admin.project.api.dto.ProbeOutcomeView testCredential(
            long id, com.sixbean.testpike.admin.credential.api.dto.CredentialTestRequest request) {
        return call(HttpMethod.POST, BASE + "/" + id + "/test", request,
                new ParameterizedTypeReference<Response<com.sixbean.testpike.admin.project.api.dto.ProbeOutcomeView>>() {
                });
    }

    @Override
    public java.util.List<com.sixbean.testpike.admin.ai.api.dto.AiTaskEventView> testEvents(
            long id, String runToken, long cursor) {
        return call(HttpMethod.GET, BASE + "/" + id + "/test-events?runToken=" + runToken
                + "&cursor=" + cursor, null,
                new ParameterizedTypeReference<Response<java.util.List<com.sixbean.testpike.admin.ai.api.dto.AiTaskEventView>>>() {
                });
    }

    @Override
    public java.util.List<com.sixbean.testpike.admin.ai.api.dto.AiTaskRunView> testRuns(long id, Integer limit) {
        return call(HttpMethod.GET, BASE + "/" + id + "/test-runs"
                + (limit == null ? "" : "?limit=" + limit), null,
                new ParameterizedTypeReference<Response<java.util.List<com.sixbean.testpike.admin.ai.api.dto.AiTaskRunView>>>() {
                });
    }

    @Override
    public com.sixbean.testpike.admin.project.api.dto.TrailRevealView revealTestTrailField(
            long id, long eventId, String field) {
        return call(HttpMethod.GET, BASE + "/" + id + "/test-events/" + eventId + "/reveal?field="
                + java.net.URLEncoder.encode(field, java.nio.charset.StandardCharsets.UTF_8), null,
                new ParameterizedTypeReference<Response<com.sixbean.testpike.admin.project.api.dto.TrailRevealView>>() {
                });
    }

    @Override
    public com.sixbean.testpike.admin.project.api.dto.TrailFullTextView testTrailFullText(
            long id, long eventId, int from, Integer limit) {
        String uri = BASE + "/" + id + "/test-events/" + eventId + "/full?from=" + from
                + (limit == null ? "" : "&limit=" + limit);
        return call(HttpMethod.GET, uri, null,
                new ParameterizedTypeReference<Response<com.sixbean.testpike.admin.project.api.dto.TrailFullTextView>>() {
                });
    }
}
