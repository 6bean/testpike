package com.sixbean.testpike.admin.plans.infrastructure.remote.client;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.plans.api.dto.CompletionCheckView;
import com.sixbean.testpike.admin.plans.api.dto.GenerateBatchResultView;
import com.sixbean.testpike.admin.plans.api.dto.PlanAssignRequest;
import com.sixbean.testpike.admin.plans.api.dto.PlanCaseView;
import com.sixbean.testpike.admin.plans.api.dto.PlanCreateRequest;
import com.sixbean.testpike.admin.plans.api.dto.PlanDeviationView;
import com.sixbean.testpike.admin.plans.api.dto.PlanDeviationRequest;
import com.sixbean.testpike.admin.plans.api.dto.PlanProgressView;
import com.sixbean.testpike.admin.plans.api.dto.PlanSummaryRow;
import com.sixbean.testpike.admin.plans.api.dto.PlanUpdateRequest;
import com.sixbean.testpike.admin.plans.api.dto.PlanView;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.common.client.RestClientInvoker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;


@Component
public class RestCorePlanClient implements CorePlanClient {

    private final RestClient rest;

    public RestCorePlanClient(@Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public PlanView create(PlanCreateRequest request) {
        return call(HttpMethod.POST, "/internal/plans", request, new ParameterizedTypeReference<>() {
        });
    }

    @Override
    public PlanView update(long id, PlanUpdateRequest request) {
        return call(HttpMethod.PUT, "/internal/plans/" + id, request, new ParameterizedTypeReference<>() {
        });
    }

    @Override
    public PlanView get(long id) {
        return call(HttpMethod.GET, "/internal/plans/" + id, null, new ParameterizedTypeReference<>() {
        });
    }

    @Override
    public PageView<PlanView> list(long projectId, Integer status, String keyword,
                                   int page, int size, String sortBy, String sortDir) {
        StringBuilder uri = new StringBuilder("/internal/plans?projectId=").append(projectId);
        if (status != null) {
            uri.append("&status=").append(status);
        }
        if (keyword != null && !keyword.isBlank()) {
            uri.append("&keyword=").append(java.net.URLEncoder.encode(keyword, java.nio.charset.StandardCharsets.UTF_8));
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
    public List<PlanCaseView> listCases(long planId, Integer status) {
        String uri = status == null ? "/internal/plans/" + planId + "/cases"
                : "/internal/plans/" + planId + "/cases?status=" + status;
        return call(HttpMethod.GET, uri, null, new ParameterizedTypeReference<>() {
        });
    }

    @Override
    public int assign(long planId, PlanAssignRequest request) {
        return call(HttpMethod.POST, "/internal/plans/" + planId + "/assign", request,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public PlanView publish(long planId) {
        return call(HttpMethod.POST, "/internal/plans/" + planId + "/publish", null,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public GenerateBatchResultView generateBatches(long planId) {
        return call(HttpMethod.POST, "/internal/plans/" + planId + "/generate-batches", null,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public PlanProgressView progress(long planId) {
        return call(HttpMethod.GET, "/internal/plans/" + planId + "/progress", null,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public List<PlanSummaryRow> progressSummary(long projectId) {
        return call(HttpMethod.GET, "/internal/plans/progress-summary?projectId=" + projectId, null,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public CompletionCheckView completionCheck(long planId) {
        return call(HttpMethod.GET, "/internal/plans/" + planId + "/completion-check", null,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public void cancel(long planId) {
        call(HttpMethod.POST, "/internal/plans/" + planId + "/cancel", null,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public void delete(long planId) {
        delete(planId, false);
    }

    @Override
    public void delete(long planId, boolean force) {
        call(HttpMethod.DELETE, "/internal/plans/" + planId + "?force=" + force, null,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public PlanDeviationView registerDeviation(long planId, PlanDeviationRequest request) {
        return call(HttpMethod.POST, "/internal/plans/" + planId + "/deviations", request,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public List<PlanDeviationView> listDeviations(long planId) {
        return call(HttpMethod.GET, "/internal/plans/" + planId + "/deviations", null,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public void validateCaseAllowed(long planId, long caseId) {
        call(HttpMethod.GET, "/internal/plans/" + planId + "/case-allowed?caseId=" + caseId, null,
                new ParameterizedTypeReference<>() {
                });
    }

    private <T> T call(HttpMethod method, String uri, Object body,
                       ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<T>> type) {
        return RestClientInvoker.call(rest, method, uri, body, type, UserContext.currentUserId());
    }
}
