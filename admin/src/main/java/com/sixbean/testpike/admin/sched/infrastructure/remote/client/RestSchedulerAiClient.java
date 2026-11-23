package com.sixbean.testpike.admin.sched.infrastructure.remote.client;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.common.client.RestClientInvoker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Set;


@Component
public class RestSchedulerAiClient implements SchedulerAiClient {

    private static final Duration RUN_NOW_READ_TIMEOUT = Duration.ofMinutes(10);

    private final RestClient rest;

    public RestSchedulerAiClient(
            @Value("${aiservice.base-url:http://localhost:18084}") String aiBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(aiBaseUrl, RUN_NOW_READ_TIMEOUT);
    }

    @Override
    public String runNow(String job, Long operatorId) {
        Long operator = operatorId == null ? UserContext.currentUserId() : operatorId;
        return RestClientInvoker.call(rest, HttpMethod.POST,
                "/internal/scheduler/run-now?job=" + encode(job)
                        + "&operator=" + (operator == null ? "" : operator),
                null, new ParameterizedTypeReference<>() {
                }, operator);
    }

    @Override
    public Set<String> jobs() {
        Set<String> jobs = RestClientInvoker.call(rest, HttpMethod.GET,
                "/internal/scheduler/jobs", null,
                new ParameterizedTypeReference<>() {
                }, UserContext.currentUserId());
        return jobs == null ? Set.of() : jobs;
    }

    private String encode(String value) {
        return java.net.URLEncoder.encode(value == null ? "" : value.trim(),
                java.nio.charset.StandardCharsets.UTF_8);
    }
}
