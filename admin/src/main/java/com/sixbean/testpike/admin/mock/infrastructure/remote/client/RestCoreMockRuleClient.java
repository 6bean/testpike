package com.sixbean.testpike.admin.mock.infrastructure.remote.client;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.mock.api.dto.MockAiJobEstimateView;
import com.sixbean.testpike.admin.mock.api.dto.MockAiJobCreateRequest;
import com.sixbean.testpike.admin.mock.api.dto.MockAiJobDraftItemView;
import com.sixbean.testpike.admin.mock.api.dto.MockAiJobView;
import com.sixbean.testpike.admin.mock.api.dto.MockHitPageView;
import com.sixbean.testpike.admin.mock.api.dto.MockPlaybackResultView;
import com.sixbean.testpike.admin.mock.api.dto.MockRecordingPageView;
import com.sixbean.testpike.admin.mock.api.dto.MockRecordingView;
import com.sixbean.testpike.admin.mock.api.dto.MockServingEndpointPageView;
import com.sixbean.testpike.admin.mock.api.dto.MockStubDraftAdviceView;
import com.sixbean.testpike.admin.mock.api.dto.MockSpecUpgradePlanView;
import com.sixbean.testpike.admin.mock.api.dto.MockScenarioInput;
import com.sixbean.testpike.admin.mock.api.dto.MockScenarioPageView;
import com.sixbean.testpike.admin.mock.api.dto.MockScenarioView;
import com.sixbean.testpike.admin.mock.api.dto.MockSpaceInput;
import com.sixbean.testpike.admin.mock.api.dto.MockSpaceView;
import com.sixbean.testpike.admin.mock.api.dto.MockStubDraftView;
import com.sixbean.testpike.admin.mock.api.dto.MockStubImportResultView;
import com.sixbean.testpike.admin.mock.api.dto.MockStubInput;
import com.sixbean.testpike.admin.mock.api.dto.MockStubPageView;
import com.sixbean.testpike.admin.mock.api.dto.MockStubView;
import com.sixbean.testpike.common.client.RestClientInvoker;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * core mock rule-plane client RestClient implementation (pattern precedent:
 * RestCoreReleaseCandidateClient — shared call logic stays in RestClientInvoker,
 * X-Operator-Id forwarded via UserContext).
 * <p>Requests/responses map field-for-field onto the core records; the owner id
 * travels as the query parameter core declares (@RequestParam projectId/spaceId),
 * the payload as the body. No bridge-side validation — the save-chain single
 * point in core MockRuleValidator owns every gate (red line 6).
 */
@Component
public class RestCoreMockRuleClient implements CoreMockRuleClient {

    private static final String BASE = "/internal/mock";

    private final RestClient rest;

    public RestCoreMockRuleClient(@Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public long createSpace(long projectId, MockSpaceInput input) {
        return RestClientInvoker.call(rest, HttpMethod.POST,
                BASE + "/space/create?projectId=" + projectId, input,
                new ParameterizedTypeReference<Response<Long>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public List<MockSpaceView> listSpaces(long projectId) {
        return RestClientInvoker.call(rest, HttpMethod.GET,
                BASE + "/space/list?projectId=" + projectId, null,
                new ParameterizedTypeReference<Response<List<MockSpaceView>>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public MockSpaceView transitionSpace(long spaceId, int targetStatus, int expectedVersion) {
        // No body: core declares all three intake numbers as @RequestParam query
        // values; the operator rides X-Operator-Id (UserContext) so the core
        // @Audit aspect records a real operator for the publish/retire act.
        return RestClientInvoker.call(rest, HttpMethod.POST,
                BASE + "/space/transition?spaceId=" + spaceId
                        + "&targetStatus=" + targetStatus
                        + "&expectedVersion=" + expectedVersion, null,
                new ParameterizedTypeReference<Response<MockSpaceView>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public MockSpaceView updateMissPolicy(long spaceId, String missPolicy, int expectedVersion) {
        // Same intake posture as transitionSpace: core declares the numbers as
        // @RequestParam query values, the operator rides X-Operator-Id so the
        // core @Audit aspect records who moved the ladder.
        return RestClientInvoker.call(rest, HttpMethod.POST,
                BASE + "/space/policy?spaceId=" + spaceId
                        + "&missPolicy=" + missPolicy
                        + "&expectedVersion=" + expectedVersion, null,
                new ParameterizedTypeReference<Response<MockSpaceView>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public long createStub(long spaceId, MockStubInput input) {
        return RestClientInvoker.call(rest, HttpMethod.POST,
                BASE + "/stub/create?spaceId=" + spaceId, input,
                new ParameterizedTypeReference<Response<Long>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public MockStubView updateStub(long stubId, MockStubInput input) {
        return RestClientInvoker.call(rest, HttpMethod.POST,
                BASE + "/stub/update?stubId=" + stubId, input,
                new ParameterizedTypeReference<Response<MockStubView>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public void deleteStub(long stubId) {
        RestClientInvoker.call(rest, HttpMethod.DELETE,
                BASE + "/stub/delete?stubId=" + stubId, null,
                new ParameterizedTypeReference<Response<Void>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public long recentStubHits(long stubId, int days) {
        Long count = RestClientInvoker.call(rest, HttpMethod.GET,
                BASE + "/stub/recent-hits?stubId=" + stubId + "&days=" + days, null,
                new ParameterizedTypeReference<Response<Long>>() {
                }, UserContext.currentUserId());
        return count == null ? 0L : count;
    }

    @Override
    public MockServingEndpointPageView pageServingEndpoints(long projectId, int page, int pageSize,
                                                            Integer status, String method,
                                                            String keyword) {
        // Historical callers keep the routing-first default order untouched.
        return pageServingEndpoints(projectId, page, pageSize, status, method, keyword, null, null);
    }

    @Override
    public MockServingEndpointPageView pageServingEndpoints(long projectId, int page, int pageSize,
                                                            Integer status, String method,
                                                            String keyword,
                                                            String sortBy, String sortDir) {
        String url = BASE + "/serving-inventory/page?projectId=" + projectId
                + "&page=" + page + "&pageSize=" + pageSize
                + (status == null ? "" : "&status=" + status)
                + (method == null || method.isBlank() ? ""
                        : "&method=" + java.net.URLEncoder.encode(method.trim(),
                                java.nio.charset.StandardCharsets.UTF_8))
                + (keyword == null || keyword.isBlank() ? ""
                        : "&keyword=" + java.net.URLEncoder.encode(keyword.trim(),
                                java.nio.charset.StandardCharsets.UTF_8))
                + (sortBy == null || sortBy.isBlank() ? ""
                        : "&sortBy=" + java.net.URLEncoder.encode(sortBy.trim(),
                                java.nio.charset.StandardCharsets.UTF_8))
                + (sortDir == null || sortDir.isBlank() ? ""
                        : "&sortDir=" + java.net.URLEncoder.encode(sortDir.trim(),
                                java.nio.charset.StandardCharsets.UTF_8));
        return RestClientInvoker.call(rest, HttpMethod.GET, url, null,
                new ParameterizedTypeReference<Response<MockServingEndpointPageView>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public List<MockStubView> listStubs(long spaceId) {
        return RestClientInvoker.call(rest, HttpMethod.GET,
                BASE + "/stub/list?spaceId=" + spaceId, null,
                new ParameterizedTypeReference<Response<List<MockStubView>>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public MockStubPageView pageStubs(long spaceId, int page, int pageSize) {
        return RestClientInvoker.call(rest, HttpMethod.GET,
                BASE + "/stub/page?spaceId=" + spaceId + "&page=" + page + "&pageSize=" + pageSize,
                null,
                new ParameterizedTypeReference<Response<MockStubPageView>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public long createScenario(long spaceId, MockScenarioInput input) {
        return RestClientInvoker.call(rest, HttpMethod.POST,
                BASE + "/scenario/create?spaceId=" + spaceId, input,
                new ParameterizedTypeReference<Response<Long>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public long startRecording(long spaceId, String targetBaseUrl) {
        // Query-parameter intake mirrors the core controller; the target URL is
        // user-supplied so it MUST ride percent-encoded (schemes/colons/slashes
        // would otherwise truncate or reshape the query).
        String encoded = java.net.URLEncoder.encode(
                targetBaseUrl == null ? "" : targetBaseUrl, java.nio.charset.StandardCharsets.UTF_8);
        return RestClientInvoker.call(rest, HttpMethod.POST,
                BASE + "/recording/start?spaceId=" + spaceId + "&targetBaseUrl=" + encoded, null,
                new ParameterizedTypeReference<Response<Long>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public MockRecordingView stopRecording(long recordingId) {
        return RestClientInvoker.call(rest, HttpMethod.POST,
                BASE + "/recording/stop?recordingId=" + recordingId, null,
                new ParameterizedTypeReference<Response<MockRecordingView>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public List<MockRecordingView> listRecordings(long spaceId) {
        return RestClientInvoker.call(rest, HttpMethod.GET,
                BASE + "/recording/list?spaceId=" + spaceId, null,
                new ParameterizedTypeReference<Response<List<MockRecordingView>>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public MockRecordingPageView pageRecordings(long spaceId, int page, int pageSize,
                                                String sortBy, String sortDir) {
        // Optional trailing sort pair — encoded, blank never rides the wire
        // (the historical newest-first order is core's default).
        String tail = "spaceId=" + spaceId + "&page=" + page + "&pageSize=" + pageSize
                + (sortBy == null || sortBy.isBlank() ? ""
                        : "&sortBy=" + java.net.URLEncoder.encode(sortBy.trim(),
                                java.nio.charset.StandardCharsets.UTF_8))
                + (sortDir == null || sortDir.isBlank() ? ""
                        : "&sortDir=" + java.net.URLEncoder.encode(sortDir.trim(),
                                java.nio.charset.StandardCharsets.UTF_8));
        return RestClientInvoker.call(rest, HttpMethod.GET,
                BASE + "/recording/page?" + tail, null,
                new ParameterizedTypeReference<Response<MockRecordingPageView>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public List<MockStubDraftView> previewPlayback(long recordingId, String mode) {
        return RestClientInvoker.call(rest, HttpMethod.GET,
                BASE + "/recording/preview?recordingId=" + recordingId
                        + "&mode=" + java.net.URLEncoder.encode(
                                mode == null ? "" : mode, java.nio.charset.StandardCharsets.UTF_8),
                null,
                new ParameterizedTypeReference<Response<List<MockStubDraftView>>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public MockPlaybackResultView playback(long spaceId, long recordingId, String mode) {
        return RestClientInvoker.call(rest, HttpMethod.POST,
                BASE + "/recording/playback?spaceId=" + spaceId + "&recordingId=" + recordingId
                        + "&mode=" + java.net.URLEncoder.encode(
                                mode == null ? "" : mode, java.nio.charset.StandardCharsets.UTF_8),
                null,
                new ParameterizedTypeReference<Response<MockPlaybackResultView>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public List<MockScenarioView> listScenarios(long spaceId) {
        return RestClientInvoker.call(rest, HttpMethod.GET,
                BASE + "/scenario/list?spaceId=" + spaceId, null,
                new ParameterizedTypeReference<Response<List<MockScenarioView>>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public MockScenarioPageView pageScenarios(long spaceId, int page, int pageSize) {
        // Historical callers keep the name-first window untouched.
        return pageScenarios(spaceId, page, pageSize, null, null);
    }

    @Override
    public MockScenarioPageView pageScenarios(long spaceId, int page, int pageSize,
                                              String sortBy, String sortDir) {
        String tail = "spaceId=" + spaceId + "&page=" + page + "&pageSize=" + pageSize
                + (sortBy == null || sortBy.isBlank() ? ""
                        : "&sortBy=" + java.net.URLEncoder.encode(sortBy.trim(),
                                java.nio.charset.StandardCharsets.UTF_8))
                + (sortDir == null || sortDir.isBlank() ? ""
                        : "&sortDir=" + java.net.URLEncoder.encode(sortDir.trim(),
                                java.nio.charset.StandardCharsets.UTF_8));
        return RestClientInvoker.call(rest, HttpMethod.GET,
                BASE + "/scenario/page?" + tail, null,
                new ParameterizedTypeReference<Response<MockScenarioPageView>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public List<MockStubDraftView> previewStubDrafts(long specId) {
        return RestClientInvoker.call(rest, HttpMethod.GET,
                BASE + "/stub/drafts?specId=" + specId, null,
                new ParameterizedTypeReference<Response<List<MockStubDraftView>>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public List<MockStubDraftAdviceView> previewStubDraftsAdvice(long specId, Long spaceId) {
        // spaceId=null must stay out of the query string entirely (core default:
        // no target space, advice-free rows) — same shape rule as listHits matched.
        String tail = "specId=" + specId
                + (spaceId == null ? "" : "&spaceId=" + spaceId);
        return RestClientInvoker.call(rest, HttpMethod.GET,
                BASE + "/stub/drafts-advised?" + tail, null,
                new ParameterizedTypeReference<Response<List<MockStubDraftAdviceView>>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public MockSpecUpgradePlanView specUpgradePlan(long spaceId, long specId) {
        return RestClientInvoker.call(rest, HttpMethod.GET,
                BASE + "/spec-upgrade/plan?spaceId=" + spaceId + "&specId=" + specId, null,
                new ParameterizedTypeReference<Response<MockSpecUpgradePlanView>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public int specUpgradeMarkRemoved(long spaceId, long specId) {
        Integer marked = RestClientInvoker.call(rest, HttpMethod.POST,
                BASE + "/spec-upgrade/mark-removed?spaceId=" + spaceId + "&specId=" + specId, null,
                new ParameterizedTypeReference<Response<Integer>>() {
                }, UserContext.currentUserId());
        return marked == null ? 0 : marked;
    }

    @Override
    public MockStubImportResultView importStubs(long spaceId, List<MockStubDraftView> drafts) {
        return RestClientInvoker.call(rest, HttpMethod.POST,
                BASE + "/stub/import?spaceId=" + spaceId, drafts,
                new ParameterizedTypeReference<Response<MockStubImportResultView>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public MockHitPageView listHits(long spaceId, Boolean matched, int page, int pageSize) {
        // Historical callers keep the newest-first timeline untouched.
        return listHits(spaceId, matched, page, pageSize, null, null);
    }

    @Override
    public MockHitPageView listHits(long spaceId, Boolean matched, int page, int pageSize,
                                    String sortBy, String sortDir) {
        // matched=null must not appear in the query string at all (core default = both spectra)
        String tail = "spaceId=" + spaceId + "&page=" + page + "&pageSize=" + pageSize
                + (matched == null ? "" : "&matched=" + matched)
                + (sortBy == null || sortBy.isBlank() ? ""
                        : "&sortBy=" + java.net.URLEncoder.encode(sortBy.trim(),
                                java.nio.charset.StandardCharsets.UTF_8))
                + (sortDir == null || sortDir.isBlank() ? ""
                        : "&sortDir=" + java.net.URLEncoder.encode(sortDir.trim(),
                                java.nio.charset.StandardCharsets.UTF_8));
        return RestClientInvoker.call(rest, HttpMethod.GET,
                BASE + "/hit/list?" + tail, null,
                new ParameterizedTypeReference<Response<MockHitPageView>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public MockStubImportResultView promoteHit(long spaceId, long hitId) {
        return RestClientInvoker.call(rest, HttpMethod.POST,
                BASE + "/hit/promote?spaceId=" + spaceId + "&hitId=" + hitId, null,
                new ParameterizedTypeReference<Response<MockStubImportResultView>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public MockAiJobView createAiJob(MockAiJobCreateRequest request) {
        return RestClientInvoker.call(rest, HttpMethod.POST,
                BASE + "/jobs", request,
                new ParameterizedTypeReference<Response<MockAiJobView>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public MockAiJobEstimateView estimateAiJob(long jobId) {
        return RestClientInvoker.call(rest, HttpMethod.POST,
                BASE + "/jobs/" + jobId + "/estimate", null,
                new ParameterizedTypeReference<Response<MockAiJobEstimateView>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public MockAiJobView getAiJob(long jobId) {
        return RestClientInvoker.call(rest, HttpMethod.GET,
                BASE + "/jobs/" + jobId, null,
                new ParameterizedTypeReference<Response<MockAiJobView>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public List<MockAiJobView> listAiJobs(long spaceId) {
        return RestClientInvoker.call(rest, HttpMethod.GET,
                BASE + "/jobs?spaceId=" + spaceId, null,
                new ParameterizedTypeReference<Response<List<MockAiJobView>>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public List<MockStubView> confirmAiJob(long jobId, List<MockStubInput> selectedStubs) {
        return RestClientInvoker.call(rest, HttpMethod.POST,
                BASE + "/jobs/" + jobId + "/confirm", selectedStubs,
                new ParameterizedTypeReference<Response<List<MockStubView>>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public MockAiJobView ignoreAiJob(long jobId) {
        return RestClientInvoker.call(rest, HttpMethod.DELETE,
                BASE + "/jobs/" + jobId, null,
                new ParameterizedTypeReference<Response<MockAiJobView>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public List<MockAiJobDraftItemView> listAiJobDrafts(long jobId) {
        return RestClientInvoker.call(rest, HttpMethod.GET,
                BASE + "/jobs/" + jobId + "/draft-items", null,
                new ParameterizedTypeReference<Response<List<MockAiJobDraftItemView>>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public List<com.sixbean.testpike.admin.ai.api.dto.AiTaskEventView> aiJobProcessEvents(
            long jobId, long cursor, Integer limit) {
        String path = BASE + "/jobs/" + jobId + "/process-events?cursor=" + cursor
                + (limit == null ? "" : "&limit=" + limit);
        return RestClientInvoker.call(rest, HttpMethod.GET, path, null,
                new ParameterizedTypeReference<Response<List<com.sixbean.testpike.admin.ai.api.dto.AiTaskEventView>>>() {
                }, UserContext.currentUserId());
    }
}
