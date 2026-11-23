package com.sixbean.testpike.admin.deletecheck.infrastructure.remote.client;

import com.sixbean.testpike.admin.deletecheck.api.dto.DeleteCheckView;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.common.client.RestClientInvoker;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;


@Component
public class RestCoreDeleteCheckClient implements CoreDeleteCheckClient {

    private final RestClient rest;

    public RestCoreDeleteCheckClient(@Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public DeleteCheckView checkProject(long projectId) {
        return call(HttpMethod.GET, "/internal/delete-check/projects/" + projectId, null,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public DeleteCheckView checkEnv(long envId) {
        return call(HttpMethod.GET, "/internal/delete-check/envs/" + envId, null,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public DeleteCheckView checkRole(long roleId) {
        return call(HttpMethod.GET, "/internal/delete-check/roles/" + roleId, null,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public DeleteCheckView checkPlan(long planId) {
        return call(HttpMethod.GET, "/internal/delete-check/plans/" + planId, null,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public DeleteCheckView checkTag(long tagId) {
        return call(HttpMethod.GET, "/internal/delete-check/tags/" + tagId, null,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public DeleteCheckView checkCase(long caseId) {
        return call(HttpMethod.GET, "/internal/delete-check/cases/" + caseId, null,
                new ParameterizedTypeReference<>() {
                });
    }

    
    private <T> T call(HttpMethod method, String uri, Object body,
                       ParameterizedTypeReference<Response<T>> type) {
        return RestClientInvoker.call(rest, method, uri, body, type, UserContext.currentUserId());
    }
}
