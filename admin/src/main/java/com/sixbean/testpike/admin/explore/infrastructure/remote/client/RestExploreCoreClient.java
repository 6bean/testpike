package com.sixbean.testpike.admin.explore.infrastructure.remote.client;

import com.sixbean.testpike.admin.explore.api.dto.ExploreCancelView;
import com.sixbean.testpike.admin.explore.api.dto.ExploreCreateInput;
import com.sixbean.testpike.admin.explore.api.dto.ExploreEstimateView;
import com.sixbean.testpike.admin.explore.api.dto.ExploreSessionView;
import com.sixbean.testpike.admin.explore.api.dto.ExploreStepView;
import com.sixbean.testpike.admin.explore.api.dto.ExploreToCasesRequest;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.common.client.RestClientInvoker;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.Optional;


@Component
public class RestExploreCoreClient implements ExploreCoreClient {

    private final RestClient rest;

    public RestExploreCoreClient(@Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public ExploreSessionView create(long projectId, ExploreCreateInput input, long operatorId) {
        return RestClientInvoker.call(rest, HttpMethod.POST,
                "/internal/explore/sessions?projectId=" + projectId, input,
                new ParameterizedTypeReference<>() {
                }, operatorId);
    }

    @Override
    public ExploreEstimateView estimate(long projectId, ExploreCreateInput input) {
        return RestClientInvoker.call(rest, HttpMethod.POST,
                "/internal/explore/sessions/estimate?projectId=" + projectId, input,
                new ParameterizedTypeReference<>() {
                }, null);
    }

    @Override
    public ExploreSessionView get(long sessionId) {
        return RestClientInvoker.call(rest, HttpMethod.GET,
                "/internal/explore/sessions/" + sessionId, null,
                new ParameterizedTypeReference<>() {
                }, null);
    }

    @Override
    public PageView<ExploreSessionView> list(long projectId, int page, int size,
                                             String sortBy, String sortDir) {
        // optional sort pair: null/blank stays absent (core keeps the historical
        // id DESC order), non-empty is url-encoded (notification-bridge posture)
        String uri = UriComponentsBuilder.fromPath("/internal/explore/sessions")
                .queryParam("projectId", projectId)
                .queryParam("page", page)
                .queryParam("size", size)
                .queryParamIfPresent("sortBy", Optional.ofNullable(sortBy))
                .queryParamIfPresent("sortDir", Optional.ofNullable(sortDir))
                .build().encode().toUriString();
        return RestClientInvoker.call(rest, HttpMethod.GET, uri, null,
                new ParameterizedTypeReference<>() {
                }, null);
    }

    @Override
    public List<ExploreStepView> steps(long sessionId) {
        return RestClientInvoker.call(rest, HttpMethod.GET,
                "/internal/explore/sessions/" + sessionId + "/steps", null,
                new ParameterizedTypeReference<>() {
                }, null);
    }

    @Override
    public List<com.sixbean.testpike.admin.ai.api.dto.AiTaskEventView> processEvents(
            long sessionId, long cursor, Integer limit) {
        String path = "/internal/explore/sessions/" + sessionId + "/process-events?cursor=" + cursor
                + (limit == null ? "" : "&limit=" + limit);
        return RestClientInvoker.call(rest, HttpMethod.GET, path, null,
                new ParameterizedTypeReference<>() {
                }, null);
    }

    @Override
    public List<com.sixbean.testpike.admin.ai.api.dto.AiTaskRunView> processRuns(
            long sessionId, Integer limit) {
        String path = "/internal/explore/sessions/" + sessionId + "/process-runs"
                + (limit == null ? "" : "?limit=" + limit);
        return RestClientInvoker.call(rest, HttpMethod.GET, path, null,
                new ParameterizedTypeReference<>() {
                }, null);
    }

    @Override
    public String screenshotUrl(long sessionId, int seq) {
        return RestClientInvoker.call(rest, HttpMethod.GET,
                "/internal/explore/sessions/" + sessionId + "/steps/" + seq + "/screenshot", null,
                new ParameterizedTypeReference<>() {
                }, null);
    }

    @Override
    public List<Long> toCases(long sessionId, ExploreToCasesRequest request, long operatorId) {
        return RestClientInvoker.call(rest, HttpMethod.POST,
                "/internal/explore/sessions/" + sessionId + "/to-cases", request,
                new ParameterizedTypeReference<>() {
                }, operatorId);
    }

    @Override
    public ExploreCancelView cancelSession(long sessionId) {
        return RestClientInvoker.call(rest, HttpMethod.DELETE,
                "/internal/explore/sessions/" + sessionId, null,
                new ParameterizedTypeReference<>() {
                }, null);
    }
}
