package com.sixbean.testpike.admin.perf.infrastructure.remote.client;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.perf.api.dto.PerfApprovalApplyRequest;
import com.sixbean.testpike.admin.perf.api.dto.PerfGateCheckRequest;
import com.sixbean.testpike.admin.perf.api.dto.PerfGateDecisionView;
import com.sixbean.testpike.admin.perf.api.dto.PerfRunPageView;
import com.sixbean.testpike.admin.perf.api.dto.PerfScenarioPageView;
import com.sixbean.testpike.admin.perf.api.dto.PerfScenarioSaveRequest;
import com.sixbean.testpike.admin.perf.api.dto.PerfTemplateDto;
import com.sixbean.testpike.admin.perf.api.dto.PerfWhitelistSaveRequest;
import com.sixbean.testpike.common.client.RestClientInvoker;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;


@Component
public class RestCorePerfClient implements CorePerfClient {

    private static final String BASE = "/internal/perf";

    private final RestClient rest;

    /** Model-leg doors (scenario draft + run insight) share a widened read
     *  budget: one click = one real LLM hop, and a live run showed the hop can
     *  exceed the default 30s bridge budget (vision close-out 2026-10-26). The
     *  core→ai-service leg (90s) fires first, so its honest wording still
     *  rides through untranslated; this leg only caps the admin-side wait. */
    private final RestClient modelLegRest;

    public RestCorePerfClient(@Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
        this.modelLegRest = RestClientInvoker.defaultRestClient(coreBaseUrl, java.time.Duration.ofSeconds(100));
    }

    @Override
    public long createWhitelist(PerfWhitelistSaveRequest request) {
        Long id = call(HttpMethod.POST, BASE + "/whitelist?projectId=" + request.projectId(), request,
                new ParameterizedTypeReference<Response<Long>>() {
                });
        return id == null ? 0L : id;
    }

    @Override
    public void deleteWhitelist(long projectId, long id) {
        call(HttpMethod.DELETE, BASE + "/whitelist/" + id + "?projectId=" + projectId, null,
                new ParameterizedTypeReference<Response<Void>>() {
                });
    }

    @Override
    public List<Map<String, Object>> listWhitelist(long projectId) {
        return call(HttpMethod.GET, BASE + "/whitelist?projectId=" + projectId, null,
                new ParameterizedTypeReference<Response<List<Map<String, Object>>>>() {
                });
    }

    @Override
    public long applyApproval(PerfApprovalApplyRequest request) {
        Long id = call(HttpMethod.POST, BASE + "/approvals?projectId=" + request.projectId(), request,
                new ParameterizedTypeReference<Response<Long>>() {
                });
        return id == null ? 0L : id;
    }

    @Override
    public void approveApproval(long id, long approverId) {
        call(HttpMethod.POST, BASE + "/approvals/" + id + "/approve", null,
                new ParameterizedTypeReference<Response<Void>>() {
                });
    }

    @Override
    public void rejectApproval(long id, long approverId, String rejectReason) {
        call(HttpMethod.POST, BASE + "/approvals/" + id + "/reject", Map.of("rejectReason", rejectReason),
                new ParameterizedTypeReference<Response<Void>>() {
                });
    }

    @Override
    public List<Map<String, Object>> listApprovals(long projectId) {
        return call(HttpMethod.GET, BASE + "/approvals?projectId=" + projectId, null,
                new ParameterizedTypeReference<Response<List<Map<String, Object>>>>() {
                });
    }

    @Override
    public PerfGateDecisionView precheck(PerfGateCheckRequest request) {
        return call(HttpMethod.POST, BASE + "/gate/precheck", request,
                new ParameterizedTypeReference<Response<PerfGateDecisionView>>() {
                });
    }

    @Override
    public int stopRun(long projectId, long runId) {
        Integer cancelled = call(HttpMethod.POST,
                BASE + "/runs/" + runId + "/stop?projectId=" + projectId, null,
                new ParameterizedTypeReference<Response<Integer>>() {
                });
        return cancelled == null ? 0 : cancelled;
    }

    @Override
    public void markRun(long projectId, long runId, String mark) {
        call(HttpMethod.POST, BASE + "/runs/" + runId + "/mark?projectId=" + projectId
                + "&mark=" + mark, null,
                new ParameterizedTypeReference<Response<Void>>() {
                });
    }

    @Override
    public int stopAllRuns(long projectId) {
        Integer stopped = call(HttpMethod.POST, BASE + "/runs/stop-all?projectId=" + projectId, null,
                new ParameterizedTypeReference<Response<Integer>>() {
                });
        return stopped == null ? 0 : stopped;
    }

    @Override
    public int sendReportCard(long projectId, long runId) {
        Integer sent = call(HttpMethod.POST,
                BASE + "/runs/" + runId + "/report-card?projectId=" + projectId, null,
                new ParameterizedTypeReference<Response<Integer>>() {
                });
        return sent == null ? 0 : sent;
    }

    private <T> T call(HttpMethod method, String uri, Object body,
                       ParameterizedTypeReference<Response<T>> type) {
        return RestClientInvoker.call(rest, method, uri, body, type, UserContext.currentUserId());
    }

    /** Doors that ride one real model hop (draft/insight) — same forwarding
     *  posture, only the read budget differs (see modelLegRest). */
    private <T> T callModelLeg(HttpMethod method, String uri, Object body,
                               ParameterizedTypeReference<Response<T>> type) {
        return RestClientInvoker.call(modelLegRest, method, uri, body, type, UserContext.currentUserId());
    }

    @Override
    public List<Map<String, Object>> listScenarios(long projectId) {
        return call(HttpMethod.GET, BASE + "/scenarios?projectId=" + projectId, null,
                new ParameterizedTypeReference<Response<List<Map<String, Object>>>>() {
                });
    }

    @Override
    public List<PerfTemplateDto> listTemplates() {
        return call(HttpMethod.GET, BASE + "/templates", null,
                new ParameterizedTypeReference<Response<List<PerfTemplateDto>>>() {
                });
    }

    @Override
    public List<Map<String, Object>> engines() {
        return call(HttpMethod.GET, BASE + "/engines", null,
                new ParameterizedTypeReference<Response<List<Map<String, Object>>>>() {
                });
    }

    @Override
    public Map<String, Object> resourcePreflight(long projectId, long scenarioId) {
        return call(HttpMethod.GET, BASE + "/resource-preflight?projectId=" + projectId
                        + "&scenarioId=" + scenarioId, null,
                new ParameterizedTypeReference<Response<Map<String, Object>>>() {
                });
    }

    @Override
    public PerfScenarioPageView pageScenarios(long projectId, int page, int pageSize,
                                              String sortBy, String sortDir, long programId) {
        // Optional trailing sort pair — encoded, blank never rides the wire
        // (the historical newest-first order is core's default). The program lens rides
        // only when set .
        String tail = "projectId=" + projectId + "&page=" + page + "&pageSize=" + pageSize
                + (programId > 0 ? "&programId=" + programId : "")
                + (sortBy == null || sortBy.isBlank() ? ""
                        : "&sortBy=" + java.net.URLEncoder.encode(sortBy.trim(),
                                java.nio.charset.StandardCharsets.UTF_8))
                + (sortDir == null || sortDir.isBlank() ? ""
                        : "&sortDir=" + java.net.URLEncoder.encode(sortDir.trim(),
                                java.nio.charset.StandardCharsets.UTF_8));
        return call(HttpMethod.GET, BASE + "/scenarios/page?" + tail, null,
                new ParameterizedTypeReference<Response<PerfScenarioPageView>>() {
                });
    }

    @Override
    public Map<String, Object> viewScenario(long projectId, long id) {
        return call(HttpMethod.GET, BASE + "/scenarios/" + id + "?projectId=" + projectId, null,
                new ParameterizedTypeReference<Response<Map<String, Object>>>() {
                });
    }

    @Override
    public long createScenario(PerfScenarioSaveRequest request) {
        Long id = call(HttpMethod.POST, BASE + "/scenarios?projectId=" + request.projectId(),
                request, new ParameterizedTypeReference<Response<Long>>() {
                });
        return id == null ? 0L : id;
    }

    @Override
    public void updateScenario(long projectId, long id, PerfScenarioSaveRequest request) {
        call(HttpMethod.PUT, BASE + "/scenarios/" + id + "?projectId=" + projectId, request,
                new ParameterizedTypeReference<Response<Void>>() {
                });
    }

    @Override
    public Map<String, Object> scenarioDeleteImpact(long projectId, long id) {
        return call(HttpMethod.GET, BASE + "/scenarios/" + id + "/delete-impact?projectId=" + projectId,
                null, new ParameterizedTypeReference<Response<Map<String, Object>>>() {
                });
    }

    @Override
    public void deleteScenario(long projectId, long id) {
        call(HttpMethod.DELETE, BASE + "/scenarios/" + id + "?projectId=" + projectId, null,
                new ParameterizedTypeReference<Response<Void>>() {
                });
    }

    @Override
    public long launchRun(long projectId, long scenarioId, Long envId) {
        // GB-1: the override travels as an optional query param — absent means the
        // scenario default (core resolves it once, no admin-side fallback logic).
        String envSuffix = envId == null ? "" : "&envId=" + envId;
        Long id = call(HttpMethod.POST,
                BASE + "/scenarios/" + scenarioId + "/runs?projectId=" + projectId + envSuffix, null,
                new ParameterizedTypeReference<Response<Long>>() {
                });
        return id == null ? 0L : id;
    }

    @Override
    public List<Map<String, Object>> listRuns(long projectId, long scenarioId) {
        return call(HttpMethod.GET, BASE + "/runs?projectId=" + projectId + "&scenarioId=" + scenarioId,
                null, new ParameterizedTypeReference<Response<List<Map<String, Object>>>>() {
                });
    }

    @Override
    public PerfRunPageView pageRuns(long projectId, long scenarioId, int page, int pageSize) {
        return call(HttpMethod.GET, BASE + "/runs/page?projectId=" + projectId
                        + "&scenarioId=" + scenarioId + "&page=" + page + "&pageSize=" + pageSize,
                null, new ParameterizedTypeReference<Response<PerfRunPageView>>() {
                });
    }

    @Override
    public PerfRunPageView pageProjectRuns(long projectId, int page, int pageSize,
                                           String sortBy, String sortDir,
                                           Integer status, String mark) {
        StringBuilder url = new StringBuilder(BASE + "/runs/project-page?projectId=").append(projectId)
                .append("&page=").append(page).append("&pageSize=").append(pageSize);
        com.sixbean.testpike.common.client.SortQueryPassThrough.append(url, sortBy, sortDir);
        if (status != null) {
            url.append("&status=").append(status);
        }
        com.sixbean.testpike.common.client.SortQueryPassThrough.appendParam(url, "mark", mark);
        return call(HttpMethod.GET, url.toString(), null,
                new ParameterizedTypeReference<Response<PerfRunPageView>>() {
                });
    }

    @Override
    public Map<String, Object> viewRun(long projectId, long runId) {
        return call(HttpMethod.GET, BASE + "/runs/" + runId + "?projectId=" + projectId, null,
                new ParameterizedTypeReference<Response<Map<String, Object>>>() {
                });
    }

    @Override
    public Map<String, Object> buildReport(long projectId, long runId) {
        return call(HttpMethod.GET, BASE + "/runs/" + runId + "/report?projectId=" + projectId, null,
                new ParameterizedTypeReference<Response<Map<String, Object>>>() {
                });
    }

    @Override
    public byte[] exportReport(long projectId, long runId, String format) {
        // raw octet stream leg — the core export door answers bytes, not the
        // Response envelope (same posture as the test-report export client)
        return rest.get().uri(java.net.URI.create(BASE + "/runs/" + runId
                        + "/report-export?projectId=" + projectId + "&format=" + format))
                .header(com.sixbean.testpike.common.client.RestClientInvoker.OPERATOR_HEADER,
                        String.valueOf(UserContext.currentUserId()))
                .retrieve()
                .body(byte[].class);
    }

    @Override
    public Map<String, Object> compareReports(long projectId, long runA, long runB) {
        return call(HttpMethod.GET,
                BASE + "/runs/compare?projectId=" + projectId + "&runA=" + runA + "&runB=" + runB, null,
                new ParameterizedTypeReference<Response<Map<String, Object>>>() {
                });
    }

    @Override
    public List<Map<String, Object>> scenarioTrend(long projectId, long scenarioId) {
        return call(HttpMethod.GET,
                BASE + "/scenarios/" + scenarioId + "/trend?projectId=" + projectId, null,
                new ParameterizedTypeReference<Response<List<Map<String, Object>>>>() {
                });
    }

    @Override
    public List<Map<String, Object>> listFrames(long projectId, long runId) {
        return call(HttpMethod.GET, BASE + "/runs/" + runId + "/frames?projectId=" + projectId, null,
                new ParameterizedTypeReference<Response<List<Map<String, Object>>>>() {
                });
    }

    @Override
    public Map<String, Object> liveCurve(long projectId, long runId, Integer sinceOffset) {
        String url = BASE + "/runs/" + runId + "/live-curve?projectId=" + projectId
                + (sinceOffset == null ? "" : "&sinceOffset=" + sinceOffset);
        return call(HttpMethod.GET, url, null,
                new ParameterizedTypeReference<Response<Map<String, Object>>>() {
                });
    }

    @Override
    public List<Map<String, Object>> listEvents(long projectId, long runId) {
        return call(HttpMethod.GET, BASE + "/runs/" + runId + "/events?projectId=" + projectId, null,
                new ParameterizedTypeReference<Response<List<Map<String, Object>>>>() {
                });
    }

    @Override
    public List<Map<String, Object>> errorClassTopN(long projectId, long runId) {
        return call(HttpMethod.GET, BASE + "/runs/" + runId + "/error-topn?projectId=" + projectId, null,
                new ParameterizedTypeReference<Response<List<Map<String, Object>>>>() {
                });
    }

    @Override
    public Map<String, Object> draftScenario(long projectId, String description) {
        // body field names mirror the core PerfScenarioDraftRequest record (Jackson maps 1:1)
        return callModelLeg(HttpMethod.POST, BASE + "/scenarios/draft",
                Map.of("projectId", projectId, "description", description),
                new ParameterizedTypeReference<Response<Map<String, Object>>>() {
                });
    }

    @Override
    public Map<String, Object> runInsight(long projectId, long runId) {
        // query-param posture like stop/mark doors — core owns the facts assembly,
        // admin forwards only the two anchors (red line: no second spec, no body parsing)
        return callModelLeg(HttpMethod.POST,
                BASE + "/runs/" + runId + "/insight?projectId=" + projectId, null,
                new ParameterizedTypeReference<Response<Map<String, Object>>>() {
                });
    }
}
