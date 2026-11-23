package com.sixbean.testpike.admin.spec.infrastructure.remote.client;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.spec.api.dto.ApiSpecView;
import com.sixbean.testpike.admin.spec.api.dto.SpecSubscriptionSaveRequest;
import com.sixbean.testpike.admin.spec.api.dto.SpecSubscriptionView;
import com.sixbean.testpike.admin.spec.api.dto.SpecUrlFetchRequest;
import com.sixbean.testpike.common.client.RestClientInvoker;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;


@Component
public class RestCoreApiSpecClient implements CoreApiSpecClient {

    private final RestClient rest;

    public RestCoreApiSpecClient(@Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public ApiSpecView upload(long projectId, String name, byte[] file) {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("file", file, MediaType.APPLICATION_OCTET_STREAM).filename(name);
        body.part("projectId", projectId);
        body.part("name", name);
        return RestClientInvoker.callMultipart(rest, "/internal/api-specs", body,
                new ParameterizedTypeReference<Response<ApiSpecView>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public ApiSpecView fetchFromUrl(long projectId, String name, String url) {
        return call(HttpMethod.POST, "/internal/api-specs/fetch-url",
                new SpecUrlFetchRequest(projectId, name, url),
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public PageView<ApiSpecView> list(long projectId, int page, int size) {
        return call(HttpMethod.GET, "/internal/api-specs?projectId=" + projectId
                + "&page=" + page + "&size=" + size, null,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public ApiSpecView get(long id) {
        return call(HttpMethod.GET, "/internal/api-specs/" + id, null,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public List<ApiSpecView> history(long projectId, String name) {
        return call(HttpMethod.GET, "/internal/api-specs/history?projectId=" + projectId
                + "&name=" + URLEncoder.encode(name, StandardCharsets.UTF_8), null,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public SpecSubscriptionView createSubscription(long projectId, String name, String url,
                                                   Integer checkIntervalMinutes, String sourceKind,
                                                   Long providerProjectId, String providerSpecName) {
        return call(HttpMethod.POST, "/internal/api-spec-subscriptions",
                new SpecSubscriptionSaveRequest(projectId, name, url, checkIntervalMinutes, null,
                        sourceKind, providerProjectId, providerSpecName),
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public SpecSubscriptionView updateSubscription(long id, String name, String url,
                                                   Integer checkIntervalMinutes, Boolean enabled,
                                                   String sourceKind, Long providerProjectId,
                                                   String providerSpecName) {
        return call(HttpMethod.PUT, "/internal/api-spec-subscriptions/" + id,
                new SpecSubscriptionSaveRequest(null, name, url, checkIntervalMinutes, enabled,
                        sourceKind, providerProjectId, providerSpecName),
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public SpecSubscriptionView getSubscription(long id) {
        return call(HttpMethod.GET, "/internal/api-spec-subscriptions/" + id, null,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public PageView<SpecSubscriptionView> listSubscriptions(long projectId, int page, int size) {
        return call(HttpMethod.GET, "/internal/api-spec-subscriptions?projectId=" + projectId
                + "&page=" + page + "&size=" + size, null,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public void deleteSubscription(long id) {
        call(HttpMethod.DELETE, "/internal/api-spec-subscriptions/" + id, null,
                new ParameterizedTypeReference<Response<Void>>() {
                });
    }

    private <T> T call(HttpMethod method, String uri, Object body,
                       ParameterizedTypeReference<Response<T>> type) {
        return RestClientInvoker.call(rest, method, uri, body, type, UserContext.currentUserId());
    }
}
