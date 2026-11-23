package com.sixbean.testpike.admin.cluster.infrastructure.remote.client;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.cluster.api.dto.UpgradePlanCreateRequest;
import com.sixbean.testpike.admin.cluster.api.dto.UpgradePlanImpactView;
import com.sixbean.testpike.admin.cluster.api.dto.UpgradePlanStepView;
import com.sixbean.testpike.admin.cluster.api.dto.UpgradePlanView;
import com.sixbean.testpike.common.client.RestClientInvoker;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;


@Component
public class RestCoreUpgradePlanClient implements CoreUpgradePlanClient {

    private static final String BASE = "/internal/cluster/upgrade-plans";

    private final RestClient rest;

    public RestCoreUpgradePlanClient(@Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public UpgradePlanView create(UpgradePlanCreateRequest request) {
        return call(HttpMethod.POST, BASE, request, new ParameterizedTypeReference<>() {
        });
    }

    @Override
    public UpgradePlanImpactView impact(UpgradePlanCreateRequest request) {
        return call(HttpMethod.POST, BASE + "/impact", request, new ParameterizedTypeReference<>() {
        });
    }

    @Override
    public List<UpgradePlanView> listAll() {
        return call(HttpMethod.GET, BASE, null, new ParameterizedTypeReference<>() {
        });
    }

    @Override
    public UpgradePlanView get(long planId) {
        return call(HttpMethod.GET, BASE + "/" + planId, null, new ParameterizedTypeReference<>() {
        });
    }

    @Override
    public List<UpgradePlanStepView> steps(long planId) {
        return call(HttpMethod.GET, BASE + "/" + planId + "/steps", null,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public UpgradePlanView start(long planId) {
        return action(planId, "start");
    }

    /**
     * Review bridge: the reviewer travels as a query parameter (core reads it as
     * @RequestParam). Values come from the login context and may hold non-ASCII
     * usernames, so the segment is URL-encoded here — the single place that knows
     * the transport shape (core controller contract).
     */
    @Override
    public UpgradePlanView review(long planId, String reviewer) {
        String encoded = java.net.URLEncoder.encode(reviewer == null ? "" : reviewer,
                java.nio.charset.StandardCharsets.UTF_8);
        return call(HttpMethod.POST, BASE + "/" + planId + "/review?reviewer=" + encoded, null,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public UpgradePlanView pause(long planId) {
        return action(planId, "pause");
    }

    @Override
    public UpgradePlanView resume(long planId) {
        return action(planId, "resume");
    }

    @Override
    public UpgradePlanView abort(long planId) {
        return action(planId, "abort");
    }

    private UpgradePlanView action(long planId, String verb) {
        return call(HttpMethod.POST, BASE + "/" + planId + "/" + verb, null,
                new ParameterizedTypeReference<>() {
                });
    }

    private <T> T call(HttpMethod method, String uri, Object body,
                       ParameterizedTypeReference<Response<T>> type) {
        return RestClientInvoker.call(rest, method, uri, body, type, UserContext.currentUserId());
    }
}
