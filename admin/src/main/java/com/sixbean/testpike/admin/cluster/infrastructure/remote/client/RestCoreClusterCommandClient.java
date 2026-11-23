package com.sixbean.testpike.admin.cluster.infrastructure.remote.client;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.cluster.api.dto.ClusterCommandView;
import com.sixbean.testpike.common.client.RestClientInvoker;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;

/**
 * RestClient implementation of the cluster command read port
 * (RestCoreExecutorPoolScaleClient precedent: GET body=null, operator id passed
 * through, shared call logic stays in RestClientInvoker). The mirrored DTO matches
 * the core record field by field so the envelope binds without custom mapping.
 */
@Component
public class RestCoreClusterCommandClient implements CoreClusterCommandClient {

    private final RestClient rest;

    public RestCoreClusterCommandClient(@Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public List<ClusterCommandView> list(Long targetExecutorId) {
        // The optional filter is omitted, not guessed: an absent targetExecutorId spans
        // the whole fleet and the predicate stays single-sourced in core.
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath("/internal/cluster/commands");
        if (targetExecutorId != null) {
            builder.queryParam("targetExecutorId", targetExecutorId);
        }
        return RestClientInvoker.call(rest, HttpMethod.GET, builder.build().encode().toUriString(), null,
                new ParameterizedTypeReference<Response<List<ClusterCommandView>>>() {
                }, UserContext.currentUserId());
    }
}
