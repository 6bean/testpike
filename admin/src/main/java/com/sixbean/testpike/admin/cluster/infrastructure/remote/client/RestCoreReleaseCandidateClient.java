package com.sixbean.testpike.admin.cluster.infrastructure.remote.client;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.cluster.api.dto.ReleaseCandidateDismissRequest;
import com.sixbean.testpike.admin.cluster.api.dto.ReleaseCandidateView;
import com.sixbean.testpike.admin.cluster.api.dto.ReleaseManifestView;
import com.sixbean.testpike.admin.cluster.api.dto.ReleaseRegisterRequest;
import com.sixbean.testpike.common.client.RestClientInvoker;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * core release-candidate client RestClient implementation (same pattern as
 * RestCoreReleaseManifestClient — shared call logic stays in RestClientInvoker,
 * X-Operator-Id forwarded via UserContext).
 * <p>Requests/responses map field-for-field onto the core records (list/pending
 * read through verbatim; confirm reuses the register input shape so the promotion
 * validation single point in core is hit with zero bridge-side processing).
 */
@Component
public class RestCoreReleaseCandidateClient implements CoreReleaseCandidateClient {

    private final RestClient rest;

    public RestCoreReleaseCandidateClient(@Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public List<ReleaseCandidateView> listPending() {
        return RestClientInvoker.call(rest, HttpMethod.GET, "/internal/cluster/release-candidate/list",
                null, new ParameterizedTypeReference<Response<List<ReleaseCandidateView>>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public int pendingCount() {
        return RestClientInvoker.call(rest, HttpMethod.GET, "/internal/cluster/release-candidate/pending-count",
                null, new ParameterizedTypeReference<Response<Integer>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public ReleaseManifestView confirm(long candidateId, ReleaseRegisterRequest request) {
        return RestClientInvoker.call(rest, HttpMethod.POST,
                "/internal/cluster/release-candidate/" + candidateId + "/confirm",
                request, new ParameterizedTypeReference<Response<ReleaseManifestView>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public ReleaseCandidateView dismiss(long candidateId, String reason) {
        return RestClientInvoker.call(rest, HttpMethod.POST,
                "/internal/cluster/release-candidate/" + candidateId + "/dismiss",
                new ReleaseCandidateDismissRequest(reason),
                new ParameterizedTypeReference<Response<ReleaseCandidateView>>() {
                }, UserContext.currentUserId());
    }
}
