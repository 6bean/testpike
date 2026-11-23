package com.sixbean.testpike.admin.coverage.infrastructure.remote.client;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.coverage.api.dto.CoverageMatrixView;
import com.sixbean.testpike.common.client.RestClientInvoker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;


@Component
public class RestCoreCoverageClient implements CoreCoverageClient {

    private final RestClient rest;

    public RestCoreCoverageClient(@Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public CoverageMatrixView matrix(long projectId, String chapter, Integer priority, String endType) {
        StringBuilder uri = new StringBuilder("/internal/projects/").append(projectId).append("/coverage");
        boolean first = true;
        if (chapter != null) {
            uri.append(first ? "?" : "&").append("chapter=").append(chapter);
            first = false;
        }
        if (priority != null) {
            uri.append(first ? "?" : "&").append("priority=").append(priority);
            first = false;
        }
        if (endType != null) {
            uri.append(first ? "?" : "&").append("endType=").append(endType);
        }
        return RestClientInvoker.call(rest, HttpMethod.GET, uri.toString(), null,
                new ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<CoverageMatrixView>>() {
                },
                UserContext.currentUserId());
    }
}
