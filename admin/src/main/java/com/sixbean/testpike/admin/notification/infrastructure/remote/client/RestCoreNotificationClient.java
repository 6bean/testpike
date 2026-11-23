package com.sixbean.testpike.admin.notification.infrastructure.remote.client;

import com.sixbean.testpike.admin.notification.api.dto.NotificationCreateRequest;
import com.sixbean.testpike.admin.notification.api.dto.NotificationView;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.common.client.RestClientInvoker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;
import java.util.Optional;


@Component
public class RestCoreNotificationClient implements CoreNotificationClient {

    private final RestClient rest;

    public RestCoreNotificationClient(@Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public long create(NotificationCreateRequest request) {
        Map<String, Long> body = RestClientInvoker.call(rest, HttpMethod.POST,
                "/internal/notifications", request,
                new ParameterizedTypeReference<>() {
                }, null);
        return body == null ? 0 : body.getOrDefault("id", 0L);
    }

    @Override
    public PageView<NotificationView> list(long userId, Integer readFlag, int page, int size,
                                           String eventType, String sortBy, String sortDir) {
        String uri = UriComponentsBuilder.fromPath("/internal/notifications")
                .queryParam("userId", userId)
                .queryParamIfPresent("readFlag", Optional.ofNullable(readFlag))
                .queryParam("page", page)
                .queryParam("size", size)
                .queryParamIfPresent("eventType", Optional.ofNullable(eventType))
                .queryParamIfPresent("sortBy", Optional.ofNullable(sortBy))
                .queryParamIfPresent("sortDir", Optional.ofNullable(sortDir))
                .build().encode().toUriString();
        return RestClientInvoker.call(rest, HttpMethod.GET, uri, null,
                new ParameterizedTypeReference<>() {
                }, null);
    }

    @Override
    public void markRead(long id, long userId) {
        RestClientInvoker.call(rest, HttpMethod.PUT,
                "/internal/notifications/" + id + "/read?userId=" + userId, null,
                new ParameterizedTypeReference<>() {
                }, null);
    }

    @Override
    public int markAllRead(long userId) {
        Map<String, Integer> result = RestClientInvoker.call(rest, HttpMethod.PUT,
                "/internal/notifications/read-all?userId=" + userId, null,
                new ParameterizedTypeReference<>() {
                }, null);
        return result == null ? 0 : result.getOrDefault("affected", 0);
    }
}
