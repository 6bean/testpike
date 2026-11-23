package com.sixbean.testpike.admin.repair.infrastructure.remote.client;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.repair.api.dto.ScriptRepairView;
import com.sixbean.testpike.common.client.RestClientInvoker;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;


@Component
public class RestScriptRepairClient implements ScriptRepairClient {

    private final RestClient rest;

    public RestScriptRepairClient(@Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public PageView<ScriptRepairView> list(Long projectId, Long caseId, int page, int size) {
        String uri = UriComponentsBuilder.fromPath("/internal/script-repairs")
                .queryParam("projectId", projectId)
                .queryParam("caseId", caseId)
                .queryParam("page", page)
                .queryParam("size", size)
                .build(false).toUriString();
        return RestClientInvoker.call(rest, HttpMethod.GET, uri, null,
                new ParameterizedTypeReference<Response<PageView<ScriptRepairView>>>() {
                }, UserContext.currentUserId());
    }
}
