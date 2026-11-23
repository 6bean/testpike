package com.sixbean.testpike.api.notify.infrastructure.remote.client;

import com.sixbean.testpike.common.client.RestClientInvoker;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;


@Component
public class RestCoreNotificationReadClient implements NotificationReadClient {

    private final RestClient rest;

    public RestCoreNotificationReadClient(
            @Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public void markRead(long notificationId, long userId) {
        RestClientInvoker.call(rest, HttpMethod.PUT,
                "/internal/notifications/" + notificationId + "/read?userId=" + userId,
                null, new ParameterizedTypeReference<Response<Void>>() {
                }, userId);
    }
}
