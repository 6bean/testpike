package com.sixbean.testpike.admin.search.infrastructure.remote.client;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.search.api.dto.SearchResultsView;
import com.sixbean.testpike.common.client.RestClientInvoker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Optional;


@Component
public class RestCoreSearchClient implements CoreSearchClient {

    private final RestClient rest;

    public RestCoreSearchClient(@Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public SearchResultsView search(boolean allVisible, String keyword) {
        String uri = UriComponentsBuilder.fromPath("/internal/search")
                .queryParam("userId", UserContext.currentUserId())
                .queryParam("allVisible", allVisible)
                .queryParamIfPresent("keyword", Optional.ofNullable(keyword))
                .build().encode().toUriString();
        return RestClientInvoker.call(rest, HttpMethod.GET, uri, null,
                new ParameterizedTypeReference<>() {
                }, UserContext.currentUserId());
    }
}
