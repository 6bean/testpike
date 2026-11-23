package com.sixbean.testpike.admin.reportcenter.infrastructure.remote.client;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.reportcenter.api.dto.ReportCenterDetailView;
import com.sixbean.testpike.admin.reportcenter.api.dto.ReportCenterGenerateInput;
import com.sixbean.testpike.admin.reportcenter.api.dto.ReportCenterListItemView;
import com.sixbean.testpike.admin.reportcenter.api.dto.ReportCenterProgramOverviewView;
import com.sixbean.testpike.admin.reportcenter.api.dto.ReportCenterQueryInput;
import com.sixbean.testpike.admin.core.infrastructure.remote.client.HttpForwardHeaders;
import com.sixbean.testpike.common.client.RestClientInvoker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;


@Component
public class RestCoreReportCenterClient implements CoreReportCenterClient {

    private static final String BASE = "/internal/report-center";

    private final RestClient rest;

    public RestCoreReportCenterClient(
            @Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public PageView<ReportCenterListItemView> list(long operatorId, ReportCenterQueryInput query) {
        return RestClientInvoker.call(rest, HttpMethod.GET, buildListUri(query), null,
                new ParameterizedTypeReference<>() {
                }, operatorId);
    }

    @Override
    public ReportCenterProgramOverviewView programOverview(long operatorId, long programId,
            String from, String to) {
        StringBuilder uri = new StringBuilder(BASE).append("/program-overview?programId=")
                .append(programId);
        if (from != null && !from.isBlank()) {
            uri.append("&from=").append(enc(from));
        }
        if (to != null && !to.isBlank()) {
            uri.append("&to=").append(enc(to));
        }
        return RestClientInvoker.call(rest, HttpMethod.GET, uri.toString(), null,
                new ParameterizedTypeReference<>() {
                }, operatorId);
    }

    @Override
    public ReportCenterDetailView detail(long id) {
        return RestClientInvoker.call(rest, HttpMethod.GET, BASE + "/" + id, null,
                new ParameterizedTypeReference<>() {
                }, UserContext.currentUserId());
    }

    @Override
    public ReportCenterDetailView generate(long operatorId, ReportCenterGenerateInput input) {
        return RestClientInvoker.call(rest, HttpMethod.POST, BASE + "/generate", input,
                new ParameterizedTypeReference<>() {
                }, operatorId);
    }

    @Override
    public ResponseEntity<byte[]> export(long operatorId, long id, String format, String theme) {
        StringBuilder uri = new StringBuilder(BASE).append('/').append(id)
                .append("/export?format=").append(enc(format));
        if (theme != null && !theme.isBlank()) {
            uri.append("&theme=").append(enc(theme));
        }
        ResponseEntity<byte[]> resp = rest.get().uri(URI.create(uri.toString()))
                .header(RestClientInvoker.OPERATOR_HEADER, String.valueOf(operatorId))
                .retrieve()
                .toEntity(new ParameterizedTypeReference<>() {
                });
        if (resp == null) {
            return ResponseEntity.internalServerError().build();
        }
        return ResponseEntity.status(resp.getStatusCode())
                .headers(HttpForwardHeaders.strip(resp.getHeaders()))
                .body(resp.getBody());
    }

    
    public static String buildListUri(ReportCenterQueryInput q) {
        StringBuilder uri = new StringBuilder(BASE)
                .append("?page=").append(q.page())
                .append("&size=").append(q.size());
        appendIfPresent(uri, "reportType", q.reportType());
        appendIfPresent(uri, "scopeType", q.scopeType());
        appendIfPresent(uri, "scopeId", q.scopeId());
        appendIfPresent(uri, "periodFrom", q.periodFrom());
        appendIfPresent(uri, "periodTo", q.periodTo());
        appendIfPresent(uri, "sortBy", q.sortBy());
        appendIfPresent(uri, "sortDir", q.sortDir());
        if (q.programId() > 0) {
            // Only a chosen lens rides the wire; 0 = no lens stays byte-identical
            // to the pre-lens request shape (legacy channel untouched).
            uri.append("&programId=").append(q.programId());
        }
        return uri.toString();
    }

    private static void appendIfPresent(StringBuilder uri, String name, Object value) {
        if (value == null || (value instanceof String s && s.isBlank())) {
            return;
        }
        uri.append('&').append(name).append('=').append(enc(String.valueOf(value)));
    }

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
