package com.sixbean.testpike.admin.defects.infrastructure.remote.client;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.defects.api.dto.DefectLinkRequest;
import com.sixbean.testpike.admin.defects.api.dto.DefectStatusRequest;
import com.sixbean.testpike.admin.defects.api.dto.DefectView;
import com.sixbean.testpike.common.client.RestClientInvoker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;


@Component
public class RestCoreDefectClient implements CoreDefectClient {

    private final RestClient rest;

    public RestCoreDefectClient(@Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public DefectView link(DefectLinkRequest request) {
        return call(HttpMethod.POST, "/internal/defects", request, new ParameterizedTypeReference<>() {
        });
    }

    @Override
    public DefectView updateStatus(long id, DefectStatusRequest request) {
        return call(HttpMethod.PUT, "/internal/defects/" + id + "/status", request,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public DefectView get(long id) {
        return call(HttpMethod.GET, "/internal/defects/" + id, null, new ParameterizedTypeReference<>() {
        });
    }

    @Override
    public List<DefectView> listByCase(long caseId) {
        return call(HttpMethod.GET, "/internal/defects?caseId=" + caseId, null,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public long rerunHint(long caseId) {
        return call(HttpMethod.POST, "/internal/defects/cases/" + caseId + "/rerun-hint", null,
                new ParameterizedTypeReference<>() {
                });
    }

    private <T> T call(HttpMethod method, String uri, Object body,
                       ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<T>> type) {
        return RestClientInvoker.call(rest, method, uri, body, type, UserContext.currentUserId());
    }
}
