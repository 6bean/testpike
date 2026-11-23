package com.sixbean.testpike.admin.cases.infrastructure.remote.client;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.batch.api.dto.BatchStartResultView;
import com.sixbean.testpike.admin.cases.api.dto.AssignBatchResultView;
import com.sixbean.testpike.admin.cases.api.dto.AssignResultView;
import com.sixbean.testpike.admin.cases.api.dto.AutoAssignResultView;
import com.sixbean.testpike.admin.cases.api.dto.BatchEditResultView;
import com.sixbean.testpike.admin.cases.api.dto.BatchReviewResultView;
import com.sixbean.testpike.admin.cases.api.dto.CaseExportResultView;
import com.sixbean.testpike.admin.cases.api.dto.CaseRecycleView;
import com.sixbean.testpike.admin.cases.api.dto.CaseReviewLogView;
import com.sixbean.testpike.admin.cases.api.dto.CaseRollbackResultView;
import com.sixbean.testpike.admin.cases.api.dto.CaseVersionDiffView;
import com.sixbean.testpike.admin.cases.api.dto.CaseVersionView;
import com.sixbean.testpike.admin.cases.api.dto.CaseView;
import com.sixbean.testpike.admin.cases.api.dto.CreateCaseRequest;
import com.sixbean.testpike.admin.cases.api.dto.ExportCasesRequest;
import com.sixbean.testpike.admin.cases.api.dto.ReviewCaseRequest;
import com.sixbean.testpike.admin.cases.api.dto.ReviewLockView;
import com.sixbean.testpike.admin.cases.api.dto.ReviewResultView;
import com.sixbean.testpike.admin.cases.api.dto.UpdateCaseRequest;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.common.client.RestClientInvoker;


import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;


@Component
public class RestCoreCaseClient implements CoreCaseClient {

    private final RestClient rest;

    public RestCoreCaseClient(@Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public List<com.sixbean.testpike.admin.cases.api.dto.FlakyRestoreProposalView> flakyRestoreProposals(
            long projectId) {
        String uri = UriComponentsBuilder.fromPath("/internal/cases/flaky-proposals")
                .queryParam("projectId", projectId)
                .build().encode().toUriString();
        return call(HttpMethod.GET, uri, null, new ParameterizedTypeReference<>() {
        });
    }

    @Override
    public PageView<CaseView> listCases(long projectId, Integer status, String keyword, Integer priority,
                                        Integer confidence, Long assigneeId, Integer phase,
                                        List<Long> tagIds, String sortBy, String sortDir, int page, int size,
                                        Boolean excellentOnly, long programId,
                                        String sourceFacet, Integer evidenceGrade) {
        String uri = UriComponentsBuilder.fromPath("/internal/cases")
                .queryParam("projectId", projectId)
                .queryParamIfPresent("programId",
                        programId > 0 ? Optional.of(programId) : Optional.empty())
                .queryParamIfPresent("status", Optional.ofNullable(status))
                .queryParamIfPresent("keyword", Optional.ofNullable(keyword))
                .queryParamIfPresent("priority", Optional.ofNullable(priority))
                .queryParamIfPresent("confidence", Optional.ofNullable(confidence))
                .queryParamIfPresent("assigneeId", Optional.ofNullable(assigneeId))
                .queryParamIfPresent("phase", Optional.ofNullable(phase))
                .queryParamIfPresent("tagIds", Optional.ofNullable(tagIds))
                .queryParamIfPresent("sortBy", Optional.ofNullable(sortBy))
                .queryParamIfPresent("sortDir", Optional.ofNullable(sortDir))
                .queryParamIfPresent("excellentOnly", Optional.ofNullable(excellentOnly))
                .queryParamIfPresent("sourceFacet", Optional.ofNullable(sourceFacet))
                .queryParamIfPresent("evidenceGrade", Optional.ofNullable(evidenceGrade))
                .queryParam("page", page)
                .queryParam("size", size)
                .build().encode().toUriString();
        return call(HttpMethod.GET, uri, null, new ParameterizedTypeReference<>() {
        });
    }

    @Override
    public CaseView getCase(long caseId) {
        return call(HttpMethod.GET, "/internal/cases/" + caseId, null, new ParameterizedTypeReference<>() {
        });
    }

    @Override
    public CaseView createCase(CreateCaseRequest request) {
        return call(HttpMethod.POST, "/internal/cases", request, new ParameterizedTypeReference<>() {
        });
    }

    @Override
    public CaseView updateCase(long caseId, UpdateCaseRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        Map<String, Object> input = new LinkedHashMap<>();
        putIfNotNull(input, "title", request.title());
        putIfNotNull(input, "precondition", request.precondition());
        putIfNotNull(input, "expectedResult", request.expectedResult());
        putIfNotNull(input, "priority", request.priority());
        putIfNotNull(input, "confidence", request.confidence());
        putIfNotNull(input, "endType", request.endType());
        putIfNotNull(input, "phase", request.phase());
        putIfNotNull(input, "steps", request.steps());
        putIfNotNull(input, "tagIds", request.tagIds());
        putIfNotNull(input, "assigneeId", request.assigneeId());
        putIfNotNull(input, "automationTarget", request.automationTarget());
        putIfNotNull(input, "appautoEngine", request.appautoEngine());
        putIfNotNull(input, "appautoEnvProfileId", request.appautoEnvProfileId());
 // Declarative mock binding passes through untouched: null leaves the core
        // column alone, 0 unbinds back to the real environment; all legality
        // checks are the core MockSpaceBindingGate single point (red line 6).
        putIfNotNull(input, "mockSpaceId", request.mockSpaceId());
        body.put("input", input);
        body.put("version", request.version() == null ? 1 : request.version());
        return call(HttpMethod.PUT, "/internal/cases/" + caseId, body, new ParameterizedTypeReference<>() {
        });
    }

    @Override
    public ReviewResultView reviewCase(long caseId, ReviewCaseRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("action", request.action());
        body.put("remark", request.remark());
        body.put("version", request.version());
        body.put("feedbackTypes", request.feedbackTypes());
        return call(HttpMethod.POST, "/internal/cases/" + caseId + "/review", body,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public BatchReviewResultView batchReviewCases(long projectId, String action, String remark,
                                                  List<Long> caseIds, Boolean confirmLowConfidence,
                                                  List<String> feedbackTypes) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("projectId", projectId);
        body.put("action", action);
        body.put("remark", remark);
        body.put("caseIds", caseIds);
        body.put("confirmLowConfidence", confirmLowConfidence);
        body.put("feedbackTypes", feedbackTypes);
        return call(HttpMethod.POST, "/internal/cases/batch-review", body,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public BatchEditResultView batchEditCases(long projectId, List<Long> caseIds,
                                              Integer phase, Integer priority, List<Long> tagIds) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("projectId", projectId);
        body.put("caseIds", caseIds);
        body.put("phase", phase);
        body.put("priority", priority);
        body.put("tagIds", tagIds);
        return call(HttpMethod.POST, "/internal/cases/batch-edit", body,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public AssignResultView assignCase(long caseId, Long assigneeId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("assigneeId", assigneeId);
        return call(HttpMethod.POST, "/internal/cases/" + caseId + "/assign", body,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public AssignBatchResultView batchAssignCases(long projectId, List<Long> caseIds, Long assigneeId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("projectId", projectId);
        body.put("caseIds", caseIds);
        body.put("assigneeId", assigneeId);
        return call(HttpMethod.POST, "/internal/cases/batch-assign", body,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public AutoAssignResultView autoAssign(long projectId, List<Long> caseIds, boolean preview) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("projectId", projectId);
        body.put("caseIds", caseIds);
        body.put("preview", preview);
        return call(HttpMethod.POST, "/internal/cases/auto-assign", body,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public long deleteCase(long caseId) {
        Long id = call(HttpMethod.DELETE, "/internal/cases/" + caseId, null, new ParameterizedTypeReference<>() {
        });
        return id == null ? 0L : id;
    }

    @Override
    public ReviewLockView acquireReviewLock(long caseId) {
        return call(HttpMethod.POST, "/internal/cases/" + caseId + "/review-lock", null,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public ReviewLockView heartbeatReviewLock(long caseId) {
        return call(HttpMethod.PUT, "/internal/cases/" + caseId + "/review-lock/heartbeat", null,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public void releaseReviewLock(long caseId) {
        call(HttpMethod.DELETE, "/internal/cases/" + caseId + "/review-lock", null,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public BatchStartResultView quickExecute(long caseId, Long envId, String browser) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("envId", envId);
        body.put("browser", browser);
        return call(HttpMethod.POST, "/internal/cases/" + caseId + "/quick-execute", body,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public CaseView cloneCase(long caseId, Long targetProjectId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("targetProjectId", targetProjectId);
        return call(HttpMethod.POST, "/internal/cases/" + caseId + "/clone", body,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public List<String> checkReferences(long caseId) {
        return call(HttpMethod.GET, "/internal/cases/" + caseId + "/references", null,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public List<CaseReviewLogView> listReviewLogs(long caseId) {
        return call(HttpMethod.GET, "/internal/cases/" + caseId + "/review-logs", null,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public List<CaseVersionView> listVersions(long caseId) {
        return call(HttpMethod.GET, "/internal/cases/" + caseId + "/versions", null,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public CaseVersionDiffView diffVersion(long caseId, int version, Integer baseVersion) {
        String uri = "/internal/cases/" + caseId + "/versions/" + version + "/diff"
                + (baseVersion == null ? "" : "?baseVersion=" + baseVersion);
        return call(HttpMethod.GET, uri, null, new ParameterizedTypeReference<>() {
        });
    }

    @Override
    public CaseRollbackResultView rollback(long caseId, int targetVersion, Integer version) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", version);
        return call(HttpMethod.POST, "/internal/cases/" + caseId + "/versions/" + targetVersion
                + "/rollback", body, new ParameterizedTypeReference<>() {
        });
    }

    @Override
    public CaseView setFlaky(long caseId, boolean isolated, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("isolated", isolated);
        body.put("reason", reason);
        return call(HttpMethod.PUT, "/internal/cases/" + caseId + "/flaky", body,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public CaseView setExcellent(long caseId, boolean excellent, int version) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("excellent", excellent);
        body.put("version", version);
        return call(HttpMethod.PUT, "/internal/cases/" + caseId + "/excellent", body,
                new ParameterizedTypeReference<>() {
                });
    }

    
    private <T> T call(HttpMethod method, String uri, Object body, ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<T>> type) {
        return RestClientInvoker.call(rest, method, uri, body, type, UserContext.currentUserId());
    }

    @Override
    public PageView<CaseRecycleView> listRecycleBin(long projectId, String keyword, int page, int size) {
        String uri = UriComponentsBuilder.fromPath("/internal/cases/recycle-bin")
                .queryParam("projectId", projectId)
                .queryParamIfPresent("keyword", Optional.ofNullable(keyword))
                .queryParam("page", page)
                .queryParam("size", size)
                .build().encode().toUriString();
        return call(HttpMethod.GET, uri, null, new ParameterizedTypeReference<>() {
        });
    }

    @Override
    public CaseRecycleView recycleEntry(long caseId) {
        return call(HttpMethod.GET, "/internal/cases/" + caseId + "/recycle-entry", null,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public CaseView restoreCase(long caseId, int version) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", version);
        return call(HttpMethod.PUT, "/internal/cases/" + caseId + "/restore", body,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public long purgeCase(long caseId) {
        Long id = call(HttpMethod.DELETE, "/internal/cases/" + caseId + "/purge", null,
                new ParameterizedTypeReference<>() {
                });
        return id == null ? 0L : id;
    }

    @Override
    public long clearRecycleBin(long projectId) {
        Long cleared = call(HttpMethod.DELETE,
                "/internal/cases/recycle-bin?projectId=" + projectId, null,
                new ParameterizedTypeReference<>() {
                });
        return cleared == null ? 0L : cleared;
    }

    @Override
    public CaseExportResultView exportCases(ExportCasesRequest request) {
        return call(HttpMethod.POST, "/internal/cases/export", request,
                new ParameterizedTypeReference<>() {
                });
    }

    private static void putIfNotNull(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}
