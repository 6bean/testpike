package com.sixbean.testpike.admin.apitest.infrastructure.remote.client;

import com.sixbean.testpike.admin.apitest.api.dto.ResponseCompareView;
import com.sixbean.testpike.admin.apitest.api.dto.RunCreateRequest;
import com.sixbean.testpike.admin.apitest.api.dto.RunView;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.client.RestClientInvoker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;


@Component
public class RestCoreApiRunClient implements CoreApiRunClient {

    private static final String BASE = "/internal/apitest/runs";

    private final RestClient rest;

    public RestCoreApiRunClient(@Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public RunView createRun(RunCreateRequest request) {
        return call(HttpMethod.POST, BASE, request,
                new ParameterizedTypeReference<Response<RunView>>() {
                });
    }

    @Override
    public RunView getRun(long id) {
        return call(HttpMethod.GET, BASE + "/" + id, null,
                new ParameterizedTypeReference<Response<RunView>>() {
                });
    }

    @Override
    public List<RunView> listRunsByRequest(long requestId) {
        return call(HttpMethod.GET, BASE + "?requestId=" + requestId, null,
                new ParameterizedTypeReference<Response<List<RunView>>>() {
                });
    }

    @Override
    public ResponseCompareView compareRuns(long leftRunId, long rightRunId) {
        return call(HttpMethod.GET, BASE + "/compare?left=" + leftRunId + "&right=" + rightRunId,
                null, new ParameterizedTypeReference<Response<ResponseCompareView>>() {
                });
    }

    @Override
    public void deleteRun(long id) {
        call(HttpMethod.DELETE, BASE + "/" + id, null,
                new ParameterizedTypeReference<Response<Void>>() {
                });
    }

    @Override
    public void clearRuns(long requestId) {
        call(HttpMethod.DELETE, BASE + "?requestId=" + requestId, null,
                new ParameterizedTypeReference<Response<Void>>() {
                });
    }

    private <T> T call(HttpMethod method, String uri, Object body,
                       ParameterizedTypeReference<Response<T>> type) {
        return RestClientInvoker.call(rest, method, uri, body, type, UserContext.currentUserId());
    }
}
