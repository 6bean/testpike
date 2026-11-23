package com.sixbean.testpike.admin.perf.infrastructure.remote.client;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.perf.api.dto.PerfDatasetPageView;
import com.sixbean.testpike.common.client.RestClientInvoker;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * RestClient implementation of the perf dataset ledger bridge .
 * <p>Multipart upload reuses {@code RestClientInvoker.callMultipart} (same
 * posture as the case-import chain): file bytes plus projectId/name ride as
 * form parts, core binds them as request params. The operator header carries
 * the logged-in user id so the core audit chain closes. Query values are
 * URL-encoded because dataset names may hold non-ASCII text; core error codes
 * and human-language messages pass through untouched — admin adds no second
 * validation here (red line 6).
 */
@Component
public class RestCorePerfDatasetClient implements CorePerfDatasetClient {

    private static final String BASE = "/internal/perf/datasets";

    private final RestClient rest;

    public RestCorePerfDatasetClient(@Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        // Uploads stream file bytes through core into object storage; the import
        // chain precedent widens the read timeout to 120s for payload-heavy posts.
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl, java.time.Duration.ofSeconds(120));
    }

    @Override
    public long upload(long projectId, String name, String fileName, byte[] content) {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("file", content, MediaType.parseMediaType("text/csv")).filename(fileName);
        body.part("projectId", projectId);
        body.part("name", name);
        Long id = RestClientInvoker.callMultipart(rest, BASE, body,
                new ParameterizedTypeReference<Response<Long>>() {
                }, UserContext.currentUserId());
        return id == null ? 0L : id;
    }

    @Override
    public Map<String, Object> view(long projectId, long id) {
        return RestClientInvoker.call(rest, HttpMethod.GET,
                BASE + "/" + id + "?projectId=" + projectId, null,
                new ParameterizedTypeReference<Response<Map<String, Object>>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public PerfDatasetPageView listPaged(long projectId, int page, int pageSize,
                                         String sortBy, String sortDir, long programId) {
        StringBuilder uri = new StringBuilder(BASE)
                .append("/page?projectId=").append(projectId)
                .append("&page=").append(page)
                .append("&pageSize=").append(pageSize);
        if (programId > 0) {
            uri.append("&programId=").append(programId);
        }
        appendOptional(uri, "sortBy", sortBy);
        appendOptional(uri, "sortDir", sortDir);
        return RestClientInvoker.call(rest, HttpMethod.GET, uri.toString(), null,
                new ParameterizedTypeReference<Response<PerfDatasetPageView>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public void delete(long projectId, long id) {
        RestClientInvoker.call(rest, HttpMethod.DELETE,
                BASE + "/" + id + "?projectId=" + projectId, null,
                new ParameterizedTypeReference<Response<Void>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public Map<String, Object> allocPolicy() {
        return RestClientInvoker.call(rest, HttpMethod.GET,
                BASE + "/alloc-policy", null,
                new ParameterizedTypeReference<Response<Map<String, Object>>>() {
                }, UserContext.currentUserId());
    }

    /** Append an encoded query part only when the caller actually provided it. */
    private static void appendOptional(StringBuilder uri, String key, String value) {
        if (value != null && !value.isBlank()) {
            uri.append('&').append(key).append('=')
                    .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
        }
    }
}
