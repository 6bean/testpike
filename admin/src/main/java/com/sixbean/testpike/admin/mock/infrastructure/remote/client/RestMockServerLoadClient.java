package com.sixbean.testpike.admin.mock.infrastructure.remote.client;

import com.sixbean.testpike.admin.mock.api.dto.MockLoadStateView;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;

/**
 * mock-server load-state client RestClient implementation .
 * <p>The self-check endpoint answers with a bare view record (no Response
 * envelope — the mock-server management plane speaks counters, not business
 * codes), so this is the one admin-side remote that does not ride
 * RestClientInvoker; the timeout shape still mirrors it (finite connect+read,
 * E7 hang protection) and a dead plane is re-thrown as a human-worded
 * SERVICE_UNAVAILABLE instead of pretending an empty-but-healthy state —
 * "unknown" and "fresh" must never look alike on the inspector.
 */
@Component
public class RestMockServerLoadClient implements MockServerLoadClient {

    /** Self-check is an in-memory projection — seconds budget, never a long task. */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);
    private static final int CONNECT_TIMEOUT_MS = 3000;

    private final RestClient rest;
    private final String managementBaseUrl;

    public RestMockServerLoadClient(
            @Value("${mockserver.management-url:http://localhost:18085}") String managementBaseUrl) {
        this.managementBaseUrl = managementBaseUrl;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                java.net.http.HttpClient.newBuilder()
                        .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
                        .build());
        factory.setReadTimeout(READ_TIMEOUT);
        this.rest = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public MockLoadStateView loadState() {
        try {
            return rest.method(HttpMethod.GET)
                    .uri(URI.create(managementBaseUrl + "/load-state"))
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
        } catch (Exception e) {
            throw new ServiceException(
                    "Mock 流量面自检口无应答（管理口 " + managementBaseUrl
                            + " 不可达或超时），健康状态未知——请先确认 mock-server 已启动",
                    BizErrorCode.SERVICE_UNAVAILABLE);
        }
    }
}
