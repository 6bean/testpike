package com.sixbean.testpike.admin.cluster.infrastructure.remote.client;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.cluster.api.dto.ExecutorCompatView;
import com.sixbean.testpike.admin.cluster.api.dto.PlatformVersionView;
import com.sixbean.testpike.admin.cluster.api.dto.ReleaseManifestView;
import com.sixbean.testpike.admin.cluster.api.dto.ReleaseRegisterRequest;
import com.sixbean.testpike.common.client.RestClientInvoker;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;


@Component
public class RestCoreReleaseManifestClient implements CoreReleaseManifestClient {

    private final RestClient rest;

    public RestCoreReleaseManifestClient(@Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public ReleaseManifestView register(ReleaseRegisterRequest request) {
        return RestClientInvoker.call(rest, HttpMethod.POST, "/internal/cluster/release-manifest/register",
                request, new ParameterizedTypeReference<Response<ReleaseManifestView>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public ReleaseManifestView latest() {
        return RestClientInvoker.call(rest, HttpMethod.GET, "/internal/cluster/release-manifest/latest",
                null, new ParameterizedTypeReference<Response<ReleaseManifestView>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public List<ReleaseManifestView> listAll() {
        return RestClientInvoker.call(rest, HttpMethod.GET, "/internal/cluster/release-manifest/list",
                null, new ParameterizedTypeReference<Response<List<ReleaseManifestView>>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public List<ExecutorCompatView> compatDiff() {
        return RestClientInvoker.call(rest, HttpMethod.GET, "/internal/cluster/release-manifest/compat-diff",
                null, new ParameterizedTypeReference<Response<List<ExecutorCompatView>>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public List<ExecutorCompatView> compatDiffAgainst(String min, String max) {
        // Bounds ride as query params straight to core (verbatim forward — the verdict
        // spectrum lives core-side, the bridge never pre-scores or normalizes).
        String uri = UriComponentsBuilder.fromPath("/internal/cluster/release-manifest/compat-diff-with")
                .queryParam("min", min)
                .queryParam("max", max)
                .build().encode().toUriString();
        return RestClientInvoker.call(rest, HttpMethod.GET, uri,
                null, new ParameterizedTypeReference<Response<List<ExecutorCompatView>>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public PlatformVersionView platformVersion() {
        return RestClientInvoker.call(rest, HttpMethod.GET, "/internal/cluster/platform-version",
                null, new ParameterizedTypeReference<Response<PlatformVersionView>>() {
                }, UserContext.currentUserId());
    }
}
