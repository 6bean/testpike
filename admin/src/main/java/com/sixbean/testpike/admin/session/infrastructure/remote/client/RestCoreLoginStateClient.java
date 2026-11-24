package com.sixbean.testpike.admin.session.infrastructure.remote.client;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.project.api.dto.ProbeOutcomeView;
import com.sixbean.testpike.admin.ai.api.dto.AiTaskEventView;
import com.sixbean.testpike.admin.session.api.dto.LoginStateKeepAliveRequest;
import com.sixbean.testpike.admin.session.api.dto.LoginStateProbeRunRequest;
import com.sixbean.testpike.admin.session.api.dto.LoginStateRegisterRequest;
import com.sixbean.testpike.admin.session.api.dto.LoginStateView;
import com.sixbean.testpike.common.client.RestClientInvoker;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * RestClient implementation of the login-state bridge (RestCoreCredentialClient
 * idiom — X-Operator-Id pass-through, core wording propagated verbatim, no
 * second validation here, shared call logic in RestClientInvoker).
 * LoginStateView and the core record share field names for direct mapping.
 */
@Component
public class RestCoreLoginStateClient implements CoreLoginStateClient {

    private static final String BASE = "/internal/session-states";

    private final RestClient rest;

    public RestCoreLoginStateClient(@Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public long register(long projectId, LoginStateRegisterRequest request) {
        Long id = RestClientInvoker.call(rest, HttpMethod.POST, BASE + "?projectId=" + projectId,
                request, new ParameterizedTypeReference<Response<Long>>() {
                }, UserContext.currentUserId());
        return id == null ? 0L : id;
    }

    @Override
    public void update(long id, LoginStateRegisterRequest request) {
        RestClientInvoker.call(rest, HttpMethod.PUT, BASE + "/" + id,
                request, new ParameterizedTypeReference<Response<Void>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public LoginStateView findState(long id) {
        return RestClientInvoker.call(rest, HttpMethod.GET, BASE + "/" + id, null,
                new ParameterizedTypeReference<Response<LoginStateView>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public List<LoginStateView> listStates(long projectId, Boolean enabledOnly) {
        StringBuilder uri = new StringBuilder(BASE).append("?projectId=").append(projectId);
        if (enabledOnly != null) {
            uri.append("&enabledOnly=").append(enabledOnly);
        }
        return RestClientInvoker.call(rest, HttpMethod.GET, uri.toString(), null,
                new ParameterizedTypeReference<Response<List<LoginStateView>>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public PageView<LoginStateView> listStatesPage(long projectId, String keyword, Integer status,
                                                   String sortBy, String sortDir, int page, int size) {
        StringBuilder uri = new StringBuilder(BASE).append("/page?projectId=").append(projectId)
                .append("&page=").append(page).append("&size=").append(size);
        if (keyword != null && !keyword.isBlank()) {
            uri.append("&keyword=").append(URLEncoder.encode(keyword.trim(), StandardCharsets.UTF_8));
        }
        if (status != null) {
            uri.append("&status=").append(status);
        }
        if (sortBy != null && !sortBy.isBlank()) {
            uri.append("&sortBy=").append(URLEncoder.encode(sortBy, StandardCharsets.UTF_8));
        }
        if (sortDir != null && !sortDir.isBlank()) {
            uri.append("&sortDir=").append(URLEncoder.encode(sortDir, StandardCharsets.UTF_8));
        }
        return RestClientInvoker.call(rest, HttpMethod.GET, uri.toString(), null,
                new ParameterizedTypeReference<Response<PageView<LoginStateView>>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public LoginStateView resolveState(long projectId, String stateName, String roleScope) {
        StringBuilder uri = new StringBuilder(BASE).append("/resolve?projectId=").append(projectId);
        if (stateName != null && !stateName.isBlank()) {
            uri.append("&stateName=").append(URLEncoder.encode(stateName, StandardCharsets.UTF_8));
        }
        if (roleScope != null && !roleScope.isBlank()) {
            uri.append("&roleScope=").append(URLEncoder.encode(roleScope, StandardCharsets.UTF_8));
        }
        return RestClientInvoker.call(rest, HttpMethod.GET, uri.toString(), null,
                new ParameterizedTypeReference<Response<LoginStateView>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public void setEnabled(long id, boolean enabled) {
        RestClientInvoker.call(rest, HttpMethod.PUT, BASE + "/" + id + "/enabled?enabled=" + enabled,
                null, new ParameterizedTypeReference<Response<Void>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public void setKeepAlive(long id, LoginStateKeepAliveRequest request) {
        RestClientInvoker.call(rest, HttpMethod.PATCH, BASE + "/" + id + "/keepalive",
                request, new ParameterizedTypeReference<Response<Void>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public void delete(long id) {
        RestClientInvoker.call(rest, HttpMethod.DELETE, BASE + "/" + id, null,
                new ParameterizedTypeReference<Response<Void>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public ProbeOutcomeView probeState(long id, String url, LoginStateProbeRunRequest run) {
        String uri = BASE + "/" + id + "/probe"
                + (url == null || url.isBlank() ? "" : "?url=" + java.net.URLEncoder.encode(url,
                        java.nio.charset.StandardCharsets.UTF_8));
        return RestClientInvoker.call(rest, HttpMethod.POST, uri, run,
                new ParameterizedTypeReference<Response<ProbeOutcomeView>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public java.util.List<AiTaskEventView> probeEvents(long id, String runToken, long cursor) {
        return RestClientInvoker.call(rest, HttpMethod.GET,
                BASE + "/" + id + "/probe-events?runToken=" + runToken + "&cursor=" + cursor, null,
                new ParameterizedTypeReference<Response<java.util.List<AiTaskEventView>>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public java.util.List<com.sixbean.testpike.admin.ai.api.dto.AiTaskRunView> probeRuns(long id, Integer limit) {
        String uri = BASE + "/" + id + "/probe-runs"
                + (limit == null ? "" : "?limit=" + limit);
        return RestClientInvoker.call(rest, HttpMethod.GET, uri, null,
                new ParameterizedTypeReference<Response<java.util.List<com.sixbean.testpike.admin.ai.api.dto.AiTaskRunView>>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public com.sixbean.testpike.admin.project.api.dto.TrailRevealView revealProbeTrailField(
            long id, long eventId, String field) {
        return RestClientInvoker.call(rest, HttpMethod.GET,
                BASE + "/" + id + "/probe-events/" + eventId + "/reveal?field="
                        + java.net.URLEncoder.encode(field, java.nio.charset.StandardCharsets.UTF_8),
                null,
                new ParameterizedTypeReference<Response<com.sixbean.testpike.admin.project.api.dto.TrailRevealView>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public com.sixbean.testpike.admin.project.api.dto.TrailFullTextView probeTrailFullText(
            long id, long eventId, int from, Integer limit) {
        String uri = BASE + "/" + id + "/probe-events/" + eventId + "/full?from=" + from
                + (limit == null ? "" : "&limit=" + limit);
        return RestClientInvoker.call(rest, HttpMethod.GET, uri, null,
                new ParameterizedTypeReference<Response<com.sixbean.testpike.admin.project.api.dto.TrailFullTextView>>() {
                }, UserContext.currentUserId());
    }
}
