package com.sixbean.testpike.admin.reports.infrastructure.remote.client;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.reports.api.dto.AiQualityView;
import com.sixbean.testpike.admin.reports.api.dto.CoverageView;
import com.sixbean.testpike.admin.reports.api.dto.CompareItemView;
import com.sixbean.testpike.admin.reports.api.dto.ExecutionStatView;
import com.sixbean.testpike.admin.reports.api.dto.ExportDownloadView;
import com.sixbean.testpike.admin.reports.api.dto.ExportResultView;
import com.sixbean.testpike.admin.reports.api.dto.ExportTaskView;
import com.sixbean.testpike.admin.reports.api.dto.PersonalReviewView;
import com.sixbean.testpike.admin.reports.api.dto.PhaseCount;
import com.sixbean.testpike.admin.reports.api.dto.ReportExportRequest;
import com.sixbean.testpike.admin.reports.api.dto.TeamMemberStatView;
import com.sixbean.testpike.common.client.RestClientInvoker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;


@Component
public class RestCoreReportClient implements CoreReportClient {

    private final RestClient rest;

    public RestCoreReportClient(@Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public PersonalReviewView personalReview(long projectId, Long userId) {
        return call(HttpMethod.GET, "/internal/reports/personal-review?projectId=" + projectId
                + (userId == null ? "" : "&userId=" + userId), null,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public List<TeamMemberStatView> teamBoard(long projectId) {
        return call(HttpMethod.GET, "/internal/reports/team-board?projectId=" + projectId, null,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public AiQualityView aiQuality(long projectId) {
        return call(HttpMethod.GET, "/internal/reports/ai-quality?projectId=" + projectId, null,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public CoverageView coverage(long projectId) {
        return call(HttpMethod.GET, "/internal/reports/coverage?projectId=" + projectId, null,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public ExecutionStatView executionStat(long projectId) {
        return executionStat(projectId, null);
    }

    @Override
    public ExecutionStatView executionStat(long projectId, Long planId) {
        String url = "/internal/reports/execution-stat?projectId=" + projectId;
        if (planId != null) {
            url += "&planId=" + planId;
        }
        return call(HttpMethod.GET, url, null, new ParameterizedTypeReference<>() {
        });
    }

    @Override
    public List<PhaseCount> phaseDist(long projectId) {
        return call(HttpMethod.GET, "/internal/reports/phase-dist?projectId=" + projectId, null,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public List<CompareItemView> compare(List<Long> projectIds) {
        String joined = projectIds.stream().map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(","));
        return call(HttpMethod.GET, "/internal/reports/compare?projectIds=" + joined, null,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public ExportResultView export(ReportExportRequest request) {
        return call(HttpMethod.POST, "/internal/reports/export", request,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public List<ExportTaskView> listExportTasks(long projectId) {
        return call(HttpMethod.GET, "/internal/reports/export-tasks?projectId=" + projectId, null,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public ExportDownloadView exportTaskDownload(long projectId, long taskId) {
        return call(HttpMethod.GET,
                "/internal/reports/export-tasks/" + taskId + "/download?projectId=" + projectId, null,
                new ParameterizedTypeReference<>() {
                });
    }

    private <T> T call(HttpMethod method, String uri, Object body,
                       ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<T>> type) {
        return RestClientInvoker.call(rest, method, uri, body, type, UserContext.currentUserId());
    }
}
