package com.sixbean.testpike.admin.notification.infrastructure.remote.client;

import com.sixbean.testpike.admin.notification.api.dto.NotificationPrefToggleRequest;
import com.sixbean.testpike.admin.notification.api.dto.NotificationPrefView;
import com.sixbean.testpike.common.client.RestClientInvoker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;


@Component
public class RestCoreNotificationPrefClient implements CoreNotificationPrefClient {

    private final RestClient rest;

    public RestCoreNotificationPrefClient(@Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public List<NotificationPrefView> list(long userId) {
        String uri = UriComponentsBuilder.fromPath("/internal/notification-prefs")
                .queryParam("userId", userId)
                .build().encode().toUriString();
        return RestClientInvoker.call(rest, HttpMethod.GET, uri, null,
                new ParameterizedTypeReference<>() {
                }, null);
    }

    @Override
    public void update(long userId, List<NotificationPrefToggleRequest> toggles) {
        String uri = UriComponentsBuilder.fromPath("/internal/notification-prefs")
                .queryParam("userId", userId)
                .build().encode().toUriString();
        RestClientInvoker.call(rest, HttpMethod.PUT, uri, toggles,
                new ParameterizedTypeReference<>() {
                }, userId);
    }
}
