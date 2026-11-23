package com.sixbean.testpike.admin.dashboard.infrastructure.remote.client;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.dashboard.api.dto.DashboardOverviewView;
import com.sixbean.testpike.common.client.RestClientInvoker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;


@Component
public class RestCoreDashboardClient implements CoreDashboardClient {

    private final RestClient rest;

    public RestCoreDashboardClient(@Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public DashboardOverviewView overview(boolean allVisible) {
        String uri = UriComponentsBuilder.fromPath("/internal/dashboard/overview")
                .queryParam("userId", UserContext.currentUserId())
                .queryParam("allVisible", allVisible)
                .build().encode().toUriString();
        return RestClientInvoker.call(rest, HttpMethod.GET, uri, null,
                new ParameterizedTypeReference<>() {
                }, UserContext.currentUserId());
    }
}
