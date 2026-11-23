package com.sixbean.testpike.admin.cluster.infrastructure.remote.client;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.cluster.api.dto.FleetEnrollIssueBody;
import com.sixbean.testpike.admin.cluster.api.dto.FleetEnrollTokenIssuedView;
import com.sixbean.testpike.admin.cluster.api.dto.FleetEnrollTokenView;
import com.sixbean.testpike.common.client.RestClientInvoker;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RestClient implementation of the enroll-token port (RestCoreExecutorPoolScaleClient
 * precedent: shared call logic stays in RestClientInvoker, operator id rides the same
 * channel). The core input record EnrollTokenIssueInput binds field-by-field from the
 * map below; actorRole is appended by this side from the session-derived value the
 * controller handed in, never from the request body.
 */
@Component
public class RestCoreClusterEnrollTokenClient implements CoreClusterEnrollTokenClient {

    private final RestClient rest;

    public RestCoreClusterEnrollTokenClient(@Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public List<FleetEnrollTokenView> list() {
        return RestClientInvoker.call(rest, HttpMethod.GET, "/internal/cluster/enroll-tokens", null,
                new ParameterizedTypeReference<Response<List<FleetEnrollTokenView>>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public FleetEnrollTokenIssuedView issue(FleetEnrollIssueBody body, String actorRole) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("role", body.role());
        payload.put("projectScope", body.projectScope());
        payload.put("maxUses", body.maxUses());
        payload.put("remark", body.remark());
        payload.put("actorRole", actorRole);
        return RestClientInvoker.call(rest, HttpMethod.POST, "/internal/cluster/enroll-tokens", payload,
                new ParameterizedTypeReference<Response<FleetEnrollTokenIssuedView>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public void revoke(Long id) {
        RestClientInvoker.call(rest, HttpMethod.POST, "/internal/cluster/enroll-tokens/" + id + "/revoke", null,
                new ParameterizedTypeReference<Response<Void>>() {
                }, UserContext.currentUserId());
    }
}
