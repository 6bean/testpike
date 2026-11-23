package com.sixbean.testpike.admin.defects.infrastructure.remote.client;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.defects.api.dto.DefectCreateRequest;
import com.sixbean.testpike.admin.defects.api.dto.DefectExportResultView;
import com.sixbean.testpike.admin.defects.api.dto.DefectLibraryView;
import com.sixbean.testpike.admin.defects.api.dto.DefectSummaryView;
import com.sixbean.testpike.admin.defects.api.dto.DefectTransitionRequest;
import com.sixbean.testpike.admin.defects.api.dto.DefectUpdateRequest;
import com.sixbean.testpike.admin.defects.api.dto.ExportDefectsRequest;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.common.client.RestClientInvoker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;


@Component
public class RestCoreDefectLibraryClient implements CoreDefectLibraryClient {

    private final RestClient rest;

    public RestCoreDefectLibraryClient(@Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public DefectLibraryView create(DefectCreateRequest request) {
        return call(HttpMethod.POST, "/internal/defect-library", request, defType());
    }

    @Override
    public PageView<DefectLibraryView> list(long projectId, Integer status, Integer severity, Long assigneeId,
                                            Long caseId, String keyword, int page, int size,
                                            String sortBy, String sortDir, long programId) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath("/internal/defect-library")
                .queryParam("projectId", projectId)
                .queryParam("status", status)
                .queryParam("severity", severity)
                .queryParam("assigneeId", assigneeId)
                .queryParam("caseId", caseId)
                .queryParam("keyword", keyword)
                .queryParam("page", page)
                .queryParam("size", size);
        if (programId > 0) {
            builder.queryParam("programId", programId);
        }
        if (sortBy != null && !sortBy.isBlank()) {
            // Percent-encode: the URI is built un-encoded (build(false)), so a raw
            // space/semicolon in an injection-shaped key would break URI.create with
            // a 500 instead of reaching the core whitelist gate as an honest 400.
            builder.queryParam("sortBy", java.net.URLEncoder.encode(sortBy,
                    java.nio.charset.StandardCharsets.UTF_8));
        }
        if (sortDir != null && !sortDir.isBlank()) {
            builder.queryParam("sortDir", sortDir);
        }
        String uri = builder.build(false).toUriString();
        return call(HttpMethod.GET, uri, null, new ParameterizedTypeReference<>() {
        });
    }

    @Override
    public DefectLibraryView get(long id) {
        return call(HttpMethod.GET, "/internal/defect-library/" + id, null, defType());
    }

    @Override
    public DefectLibraryView update(long id, DefectUpdateRequest request) {
        return call(HttpMethod.PUT, "/internal/defect-library/" + id, request, defType());
    }

    @Override
    public DefectLibraryView transition(long id, DefectTransitionRequest request) {
        return call(HttpMethod.POST, "/internal/defect-library/" + id + "/transition", request, defType());
    }

    @Override
    public DefectSummaryView summaryByCase(long caseId) {
        return call(HttpMethod.GET, "/internal/defect-library/summary?caseId=" + caseId, null,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public DefectExportResultView exportDefects(ExportDefectsRequest request) {
        return call(HttpMethod.POST, "/internal/defect-library/export", request,
                new ParameterizedTypeReference<>() {
                });
    }

    private ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<DefectLibraryView>> defType() {
        return new ParameterizedTypeReference<>() {
        };
    }

    private <T> T call(HttpMethod method, String uri, Object body,
                       ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<T>> type) {
        return RestClientInvoker.call(rest, method, uri, body, type, UserContext.currentUserId());
    }
}
