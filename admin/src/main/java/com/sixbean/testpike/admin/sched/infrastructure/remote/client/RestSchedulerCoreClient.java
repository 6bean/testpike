package com.sixbean.testpike.admin.sched.infrastructure.remote.client;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.sched.api.dto.SchedulerHealthView;
import com.sixbean.testpike.common.client.RestClientInvoker;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Set;


@Component
public class RestSchedulerCoreClient implements SchedulerCoreClient {

    private static final Duration RUN_NOW_READ_TIMEOUT = Duration.ofMinutes(10);

    private final RestClient rest;

    public RestSchedulerCoreClient(
            @Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl, RUN_NOW_READ_TIMEOUT);
    }

    @Override
    public String runNow(String job, Long operatorId) {
        return RestClientInvoker.call(rest, HttpMethod.POST,
                "/internal/scheduler/run-now?job=" + encode(job), null,
                new ParameterizedTypeReference<>() {
                }, operatorId == null ? UserContext.currentUserId() : operatorId);
    }

    @Override
    public Set<String> jobs() {
        Set<String> jobs = RestClientInvoker.call(rest, HttpMethod.GET,
                "/internal/scheduler/jobs", null,
                new ParameterizedTypeReference<>() {
                }, UserContext.currentUserId());
        return jobs == null ? Set.of() : jobs;
    }

    @Override
    public PageView<SchedulerHealthView> health(String keyword, int page, int size,
                                                String sortBy, String sortDir) {
        StringBuilder uri = new StringBuilder("/internal/scheduler/health?page=")
                .append(page).append("&size=").append(size);
        if (keyword != null && !keyword.isBlank()) {
            uri.append("&keyword=").append(encode(keyword));
        }
        if (sortBy != null && !sortBy.isBlank()) {
            uri.append("&sortBy=").append(encode(sortBy));
        }
        if (sortDir != null && !sortDir.isBlank()) {
            uri.append("&sortDir=").append(encode(sortDir));
        }
        return RestClientInvoker.call(rest, HttpMethod.GET, uri.toString(), null,
                new ParameterizedTypeReference<Response<PageView<SchedulerHealthView>>>() {
                }, UserContext.currentUserId());
    }

    private String encode(String value) {
        return java.net.URLEncoder.encode(value == null ? "" : value.trim(),
                java.nio.charset.StandardCharsets.UTF_8);
    }
}
