package com.sixbean.testpike.admin.suites.infrastructure.remote.client;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.suites.api.dto.ScheduleFireView;
import com.sixbean.testpike.common.client.RestClientInvoker;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;


@Component
public class RestCoreScheduleClient implements CoreScheduleClient {

    private final RestClient rest;

    public RestCoreScheduleClient(@Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public PageView<ScheduleFireView> listFires(long projectId, String kind, String status,
                                                int page, int size, String sortBy, String sortDir) {
        StringBuilder uri = new StringBuilder("/internal/schedule/fires?projectId=").append(projectId);
        if (kind != null && !kind.isBlank()) {
            uri.append("&kind=").append(URLEncoder.encode(kind, StandardCharsets.UTF_8));
        }
        if (status != null && !status.isBlank()) {
            uri.append("&status=").append(URLEncoder.encode(status, StandardCharsets.UTF_8));
        }
        if (sortBy != null && !sortBy.isBlank()) {
            uri.append("&sortBy=").append(URLEncoder.encode(sortBy.trim(), StandardCharsets.UTF_8));
        }
        if (sortDir != null && !sortDir.isBlank()) {
            uri.append("&sortDir=").append(URLEncoder.encode(sortDir.trim(), StandardCharsets.UTF_8));
        }
        uri.append("&page=").append(page).append("&size=").append(size);
        return RestClientInvoker.call(rest, HttpMethod.GET, uri.toString(), null,
                new ParameterizedTypeReference<Response<PageView<ScheduleFireView>>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public List<Instant> previewCron(String cron, int count) {
        String uri = "/internal/schedule/cron-preview?count=" + count
                + (cron == null || cron.isBlank() ? "" : "&cron=" + URLEncoder.encode(cron, StandardCharsets.UTF_8));
        List<Instant> fires = RestClientInvoker.call(rest, HttpMethod.GET, uri, null,
                new ParameterizedTypeReference<Response<List<Instant>>>() {
                }, UserContext.currentUserId());
        return fires == null ? List.of() : fires;
    }
}
