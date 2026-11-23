package com.sixbean.testpike.admin.batch.infrastructure.remote.client;

import com.sixbean.testpike.admin.batch.api.dto.BatchCaseView;
import com.sixbean.testpike.admin.batch.api.dto.BatchCreateRequest;
import com.sixbean.testpike.admin.batch.api.dto.BatchFromTemplateRequest;
import com.sixbean.testpike.admin.batch.api.dto.BatchInfoView;
import com.sixbean.testpike.admin.batch.api.dto.BatchInsightView;
import com.sixbean.testpike.admin.batch.api.dto.BatchRecoveryView;
import com.sixbean.testpike.admin.batch.api.dto.BatchReportView;
import com.sixbean.testpike.admin.batch.api.dto.BatchStartResultView;
import com.sixbean.testpike.admin.batch.api.dto.BatchTemplateSaveRequest;
import com.sixbean.testpike.admin.batch.api.dto.BatchTemplateView;
import com.sixbean.testpike.admin.batch.api.dto.BatchUpdateRequest;
import com.sixbean.testpike.admin.batch.api.dto.TemplateScheduleRequest;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.common.client.RestClientInvoker;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;


@Component
public class RestCoreBatchClient implements CoreBatchClient {

    private final RestClient rest;

    public RestCoreBatchClient(@Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public BatchInfoView create(BatchCreateRequest request) {
        return call(HttpMethod.POST, "/internal/batches", request, new ParameterizedTypeReference<>() {
        });
    }

    @Override
    public BatchInfoView update(long id, BatchUpdateRequest request) {
        return call(HttpMethod.PUT, "/internal/batches/" + id, request,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public BatchStartResultView start(long id) {
        return call(HttpMethod.POST, "/internal/batches/" + id + "/start", null,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public BatchInfoView pause(long id, Integer version) {
        return call(HttpMethod.POST, "/internal/batches/" + id + "/pause" + versionQuery(version),
                null, new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public BatchInfoView resume(long id, Integer version) {
        return call(HttpMethod.POST, "/internal/batches/" + id + "/resume" + versionQuery(version),
                null, new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public BatchInfoView cancel(long id, Integer version) {
        return call(HttpMethod.POST, "/internal/batches/" + id + "/cancel" + versionQuery(version),
                null, new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public BatchRecoveryView recover(long id) {
        return call(HttpMethod.POST, "/internal/batches/" + id + "/recover", null,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public BatchReportView report(long id) {
        return call(HttpMethod.GET, "/internal/batches/" + id + "/report", null,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public BatchInsightView insight(long id) {
        return call(HttpMethod.GET, "/internal/batches/" + id + "/insight", null,
                new ParameterizedTypeReference<>() {
                });
    }

 
    private static String versionQuery(Integer version) {
        return version == null ? "" : "?version=" + version;
    }

    @Override
    public String skipCase(long id) {
        return call(HttpMethod.POST, "/internal/batches/" + id + "/skip-case", null,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public int rerunFailed(long id) {
        return call(HttpMethod.POST, "/internal/batches/" + id + "/rerun-failed", null,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public BatchInfoView get(long id) {
        return call(HttpMethod.GET, "/internal/batches/" + id, null,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public List<com.sixbean.testpike.admin.ai.api.dto.AiTaskEventView> processEvents(
            long id, long cursor, Integer limit) {
        String path = "/internal/batches/" + id + "/process-events?cursor=" + cursor
                + (limit == null ? "" : "&limit=" + limit);
        return call(HttpMethod.GET, path, null,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public PageView<BatchInfoView> list(long projectId, Integer status, String keyword,
                                        int page, int size, String sortBy, String sortDir,
                                        long programId) {
        StringBuilder uri = new StringBuilder("/internal/batches?projectId=").append(projectId);
        if (programId > 0) {
            uri.append("&programId=").append(programId);
        }
        if (status != null) {
            uri.append("&status=").append(status);
        }
        if (keyword != null && !keyword.isBlank()) {
            uri.append("&keyword=").append(java.net.URLEncoder.encode(keyword,
                    java.nio.charset.StandardCharsets.UTF_8));
        }
        if (sortBy != null && !sortBy.isBlank()) {
            uri.append("&sortBy=").append(java.net.URLEncoder.encode(sortBy,
                    java.nio.charset.StandardCharsets.UTF_8));
        }
        if (sortDir != null && !sortDir.isBlank()) {
            uri.append("&sortDir=").append(sortDir);
        }
        uri.append("&page=").append(page).append("&size=").append(size);
        return call(HttpMethod.GET, uri.toString(), null, new ParameterizedTypeReference<>() {
        });
    }

    @Override
    public List<BatchCaseView> listCases(long batchId) {
        return call(HttpMethod.GET, "/internal/batches/" + batchId + "/cases", null,
                new ParameterizedTypeReference<Response<List<BatchCaseView>>>() {
                });
    }

    @Override
    public BatchTemplateView saveAsTemplate(long batchId, BatchTemplateSaveRequest request) {
        return call(HttpMethod.POST, "/internal/batches/" + batchId + "/save-as-template", request,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public List<BatchTemplateView> listTemplates(long projectId) {
        return call(HttpMethod.GET, "/internal/batches/templates?projectId=" + projectId, null,
                new ParameterizedTypeReference<Response<List<BatchTemplateView>>>() {
                });
    }

    @Override
    public BatchTemplateView getTemplate(long templateId) {
        return call(HttpMethod.GET, "/internal/batches/templates/" + templateId, null,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public BatchInfoView createFromTemplate(BatchFromTemplateRequest request) {
        return call(HttpMethod.POST, "/internal/batches/from-template", request,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public void deleteTemplate(long templateId) {
        call(HttpMethod.DELETE, "/internal/batches/templates/" + templateId, null,
                new ParameterizedTypeReference<Response<Void>>() {
                });
    }

    @Override
    public BatchTemplateView updateTemplateSchedule(long templateId, TemplateScheduleRequest request) {
        return call(HttpMethod.PUT, "/internal/batches/templates/" + templateId + "/schedule",
                request, new ParameterizedTypeReference<>() {
                });
    }

    private <T> T call(HttpMethod method, String uri, Object body,
                       ParameterizedTypeReference<Response<T>> type) {
        return RestClientInvoker.call(rest, method, uri, body, type,
                com.sixbean.testpike.admin.auth.app.component.UserContext.currentUserId());
    }
}
