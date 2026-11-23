package com.sixbean.testpike.admin.favorite.infrastructure.remote.client;

import com.sixbean.testpike.admin.favorite.api.dto.FavoriteView;
import com.sixbean.testpike.common.client.RestClientInvoker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.Map;
import java.util.Optional;


@Component
public class RestCoreFavoriteClient implements CoreFavoriteClient {

    private final RestClient rest;

    public RestCoreFavoriteClient(@Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public void favorite(long userId, String entityType, long entityId) {
        RestClientInvoker.call(rest, HttpMethod.PUT,
                basePath(userId, entityType, entityId), null,
                new ParameterizedTypeReference<>() {
                }, null);
    }

    @Override
    public int unfavorite(long userId, String entityType, long entityId) {
        Map<String, Integer> result = RestClientInvoker.call(rest, HttpMethod.DELETE,
                basePath(userId, entityType, entityId), null,
                new ParameterizedTypeReference<>() {
                }, null);
        return result == null ? 0 : result.getOrDefault("affected", 0);
    }

    @Override
    public List<FavoriteView> list(long userId, boolean allVisible, String entityType) {
        String uri = UriComponentsBuilder.fromPath("/internal/favorites")
                .queryParam("userId", userId)
                .queryParam("allVisible", allVisible)
                .queryParamIfPresent("entityType", Optional.ofNullable(entityType))
                .build().encode().toUriString();
        return RestClientInvoker.call(rest, HttpMethod.GET, uri, null,
                new ParameterizedTypeReference<>() {
                }, null);
    }

    private String basePath(long userId, String entityType, long entityId) {
        return "/internal/favorites/" + entityType + "/" + entityId + "?userId=" + userId;
    }
}
