package com.sixbean.testpike.admin.webelement.infrastructure.remote.client;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.common.client.RestClientInvoker;
import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.webelement.PageWriteProfilePageView;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * RestClient implementation of the page write-risk ledger client. Operator
 * id rides on X-Operator-Id and core refusals pass through unchanged,
 * exactly like the site-profile client it sits beside.
 */
@Component
public class RestCorePageWriteProfileClient implements CorePageWriteProfileClient {

    private final RestClient rest;

    public RestCorePageWriteProfileClient(@Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public PageWriteProfilePageView pageProfiles(long projectId, int page, int pageSize, String keyword) {
        StringBuilder uri = new StringBuilder("/internal/page-write-profiles/page?projectId=")
                .append(projectId).append("&page=").append(page)
                .append("&pageSize=").append(pageSize);
        if (keyword != null && !keyword.isBlank()) {
            uri.append("&keyword=").append(URLEncoder.encode(keyword.trim(), StandardCharsets.UTF_8));
        }
        return RestClientInvoker.call(rest, HttpMethod.GET, uri.toString(), null,
                new ParameterizedTypeReference<Response<PageWriteProfilePageView>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public long caseDraftFromFailure(long projectId, long profileId, int failureIndex) {
        return RestClientInvoker.call(rest, HttpMethod.POST, "/internal/page-write-profiles/case-draft",
                Map.of("projectId", projectId, "profileId", profileId, "failureIndex", failureIndex),
                new ParameterizedTypeReference<Response<Long>>() {
                }, UserContext.currentUserId());
    }
}
