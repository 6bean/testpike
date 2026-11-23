package com.sixbean.testpike.admin.reports.infrastructure.remote.client;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.reports.api.dto.ReportDetailView;
import com.sixbean.testpike.admin.reports.api.dto.ReportGenerateRequest;
import com.sixbean.testpike.admin.reports.api.dto.ReportRecordView;
import com.sixbean.testpike.common.client.RestClientInvoker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriUtils;

import java.net.URI;
import java.nio.charset.StandardCharsets;


@Component
public class RestCoreTestReportClient implements CoreTestReportClient {

    private final RestClient rest;

    public RestCoreTestReportClient(@Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public ReportRecordView generate(ReportGenerateRequest request) {
        return RestClientInvoker.call(rest, HttpMethod.POST, "/internal/test-reports/generate",
                request, new ParameterizedTypeReference<>() {
                }, UserContext.currentUserId());
    }

    @Override
    public PageView<ReportRecordView> list(long projectId, String keyword, int page, int size,
                                           String sortBy, String sortDir) {
        String url = "/internal/test-reports?projectId=" + projectId + "&page=" + page + "&size=" + size;
        if (keyword != null && !keyword.isBlank()) {
            url += "&keyword=" + keyword;
        }
        // The optional sort pair rides through only when present; the whitelist
        // authority lives in core, this bridge forwards it untouched.
        if (sortBy != null && !sortBy.isBlank()) {
            url += "&sortBy=" + UriUtils.encodeQueryParam(sortBy, StandardCharsets.UTF_8);
        }
        if (sortDir != null && !sortDir.isBlank()) {
            url += "&sortDir=" + UriUtils.encodeQueryParam(sortDir, StandardCharsets.UTF_8);
        }
        return RestClientInvoker.call(rest, HttpMethod.GET, url, null,
                new ParameterizedTypeReference<>() {
                }, UserContext.currentUserId());
    }

    @Override
    public ReportDetailView get(long id) {
        return RestClientInvoker.call(rest, HttpMethod.GET, "/internal/test-reports/" + id, null,
                new ParameterizedTypeReference<>() {
                }, UserContext.currentUserId());
    }

    @Override
    public byte[] export(long id, String format) {
        return rest.get().uri(URI.create("/internal/test-reports/" + id + "/export?format=" + format))
                .header(RestClientInvoker.OPERATOR_HEADER, String.valueOf(UserContext.currentUserId()))
                .retrieve()
                .body(byte[].class);
    }
}
