package com.sixbean.testpike.admin.appauto.infrastructure.remote.client;

import com.sixbean.testpike.admin.appauto.api.dto.AppautoAppView;
import com.sixbean.testpike.admin.appauto.api.dto.AppautoDeviceThumbnailView;
import com.sixbean.testpike.admin.appauto.api.dto.AppautoDeviceView;
import com.sixbean.testpike.admin.appauto.api.dto.AppautoElementCaptureInput;
import com.sixbean.testpike.admin.appauto.api.dto.AppautoElementInput;
import com.sixbean.testpike.admin.appauto.api.dto.AppautoElementPageView;
import com.sixbean.testpike.admin.appauto.api.dto.AppautoElementView;
import com.sixbean.testpike.admin.appauto.api.dto.AppautoEngineView;
import com.sixbean.testpike.admin.appauto.api.dto.AppautoEngineAvailabilityView;
import com.sixbean.testpike.admin.appauto.api.dto.AppautoEngineHealthView;
import com.sixbean.testpike.admin.appauto.api.dto.AppautoEnvProfileView;
import com.sixbean.testpike.admin.appauto.api.dto.AppautoLeaseRunView;
import com.sixbean.testpike.admin.appauto.api.dto.AppautoMatrixDispatchResult;
import com.sixbean.testpike.admin.appauto.api.dto.AppautoMatrixFanoutRequest;
import com.sixbean.testpike.admin.appauto.api.dto.AppautoMatrixModelRow;
import com.sixbean.testpike.admin.appauto.api.dto.AppautoMatrixPrecheckView;
import com.sixbean.testpike.admin.appauto.api.dto.AppautoMatrixSampleRequest;
import com.sixbean.testpike.admin.appauto.api.dto.AppautoMatrixSampleView;
import com.sixbean.testpike.admin.appauto.api.dto.AppautoNlStepView;
import com.sixbean.testpike.admin.appauto.api.dto.AppautoPlatformView;
import com.sixbean.testpike.admin.appauto.api.dto.AppautoPortabilityView;
import com.sixbean.testpike.admin.appauto.api.dto.AppautoPopupRuleView;
import com.sixbean.testpike.admin.appauto.api.dto.AppautoRecordDraftView;
import com.sixbean.testpike.admin.appauto.api.dto.AppautoRecordFlowView;
import com.sixbean.testpike.admin.appauto.api.dto.AppautoReservationView;
import com.sixbean.testpike.admin.appauto.api.dto.AppautoStepSuggestionView;
import com.sixbean.testpike.admin.appauto.api.dto.AppautoSupportView;
import com.sixbean.testpike.admin.appauto.api.dto.AppautoTakeoverView;
import com.sixbean.testpike.admin.appauto.api.dto.AppautoUpgradeAdviceView;
import com.sixbean.testpike.admin.appauto.api.dto.AppautoUpgradeFlakyLinkView;
import com.sixbean.testpike.admin.appauto.api.dto.AppautoUpgradeWindowView;
import com.sixbean.testpike.admin.appauto.api.dto.AppautoWxMockView;
import com.sixbean.testpike.admin.appauto.api.dto.AppPackageSaveRequest;
import com.sixbean.testpike.admin.appauto.api.dto.DeviceSaveRequest;
import com.sixbean.testpike.admin.appauto.api.dto.EngineManageRequest;
import com.sixbean.testpike.admin.appauto.api.dto.EnvProfileSaveRequest;
import com.sixbean.testpike.admin.appauto.api.dto.PlatformManageRequest;
import com.sixbean.testpike.admin.appauto.api.dto.ReservationCreateRequest;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.common.client.RestClientInvoker;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;


@Component
public class RestCoreAppautoClient implements CoreAppautoClient {

    private static final String BASE = "/internal/appauto";

    private final RestClient rest;

    
    private final RestClient uploadRest;

    public RestCoreAppautoClient(@Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
        this.uploadRest = RestClientInvoker.defaultRestClient(coreBaseUrl, java.time.Duration.ofSeconds(120));
    }

    @Override
    public long registerDevice(DeviceSaveRequest request) {
        String query = request.projectId() == null ? "" : "?projectId=" + request.projectId();
        Long id = call(HttpMethod.POST, BASE + "/devices" + query, request,
                new ParameterizedTypeReference<Response<Long>>() {
                });
        return id == null ? 0L : id;
    }

    @Override
    public void updateDevice(long id, DeviceSaveRequest request) {
        call(HttpMethod.PUT, BASE + "/devices/" + id, request,
                new ParameterizedTypeReference<Response<Void>>() {
                });
    }

    @Override
    public AppautoDeviceView findDevice(long id) {
        return call(HttpMethod.GET, BASE + "/devices/" + id, null,
                new ParameterizedTypeReference<Response<AppautoDeviceView>>() {
                });
    }

    @Override
    public AppautoDeviceThumbnailView deviceThumbnail(long deviceId) {
        return call(HttpMethod.GET, BASE + "/devices/" + deviceId + "/thumbnail", null,
                new ParameterizedTypeReference<Response<AppautoDeviceThumbnailView>>() {
                });
    }

    @Override
    public List<AppautoDeviceView> listDevices(long projectId, String label) {
        StringBuilder uri = new StringBuilder(BASE + "/devices?projectId=").append(projectId);
        if (label != null && !label.isBlank()) {
            uri.append("&label=").append(java.net.URLEncoder.encode(label,
                    java.nio.charset.StandardCharsets.UTF_8));
        }
        return call(HttpMethod.GET, uri.toString(), null,
                new ParameterizedTypeReference<Response<List<AppautoDeviceView>>>() {
                });
    }

    @Override
    public void unquarantineDevice(long id) {
        call(HttpMethod.POST, BASE + "/devices/" + id + "/unquarantine", null,
                new ParameterizedTypeReference<Response<Void>>() {
                });
    }

    @Override
    public void deleteDevice(long id) {
        call(HttpMethod.DELETE, BASE + "/devices/" + id, null,
                new ParameterizedTypeReference<Response<Void>>() {
                });
    }

    @Override
    public List<AppautoLeaseRunView> listDeviceLeaseRuns(long deviceId) {
        return call(HttpMethod.GET, BASE + "/devices/" + deviceId + "/lease-runs", null,
                new ParameterizedTypeReference<Response<List<AppautoLeaseRunView>>>() {
                });
    }

    @Override
    public List<AppautoEngineView> listEngines(boolean includeDisabled) {
        return call(HttpMethod.GET, BASE + "/engines?includeDisabled=" + includeDisabled, null,
                new ParameterizedTypeReference<Response<List<AppautoEngineView>>>() {
                });
    }

    @Override
    public List<AppautoEngineAvailabilityView> listEngineAvailability(boolean includeDisabled) {
        return call(HttpMethod.GET, BASE + "/engines/availability?includeDisabled=" + includeDisabled,
                null,
                new ParameterizedTypeReference<Response<List<AppautoEngineAvailabilityView>>>() {
                });
    }

    @Override
    public List<AppautoEngineHealthView> listEngineHealth(boolean includeDisabled) {
        return call(HttpMethod.GET, BASE + "/engines/health?includeDisabled=" + includeDisabled,
                null,
                new ParameterizedTypeReference<Response<List<AppautoEngineHealthView>>>() {
                });
    }

    @Override
    public AppautoPortabilityView precheckPortability(long projectId, long caseId,
                                                      String engineCode) {
        StringBuilder uri = new StringBuilder(BASE + "/portability?projectId=").append(projectId)
                .append("&caseId=").append(caseId);
        if (engineCode != null && !engineCode.isBlank()) {
            uri.append("&engineCode=").append(java.net.URLEncoder.encode(engineCode,
                    java.nio.charset.StandardCharsets.UTF_8));
        }
        return call(HttpMethod.GET, uri.toString(), null,
                new ParameterizedTypeReference<Response<AppautoPortabilityView>>() {
                });
    }

    @Override
    public AppautoEngineView findEngine(String code) {
        return call(HttpMethod.GET, BASE + "/engines/" + code, null,
                new ParameterizedTypeReference<Response<AppautoEngineView>>() {
                });
    }

    @Override
    public List<AppautoPlatformView> listPlatforms(boolean includeDisabled) {
        return call(HttpMethod.GET, BASE + "/platforms?includeDisabled=" + includeDisabled, null,
                new ParameterizedTypeReference<Response<List<AppautoPlatformView>>>() {
                });
    }

    @Override
    public AppautoPlatformView findPlatform(String code) {
        return call(HttpMethod.GET, BASE + "/platforms/" + code, null,
                new ParameterizedTypeReference<Response<AppautoPlatformView>>() {
                });
    }

    @Override
    public void updateEngine(String code, EngineManageRequest request) {
        call(HttpMethod.PUT, BASE + "/engines/" + code, request,
                new ParameterizedTypeReference<Response<Void>>() {
                });
    }

    @Override
    public void updatePlatform(String code, PlatformManageRequest request) {
        call(HttpMethod.PUT, BASE + "/platforms/" + code, request,
                new ParameterizedTypeReference<Response<Void>>() {
                });
    }

    @Override
    public long createEnvProfile(long projectId, EnvProfileSaveRequest request) {
        Long id = call(HttpMethod.POST, BASE + "/env-profiles?projectId=" + projectId, request,
                new ParameterizedTypeReference<Response<Long>>() {
                });
        return id == null ? 0L : id;
    }

    @Override
    public void updateEnvProfile(long id, EnvProfileSaveRequest request) {
        call(HttpMethod.PUT, BASE + "/env-profiles/" + id, request,
                new ParameterizedTypeReference<Response<Void>>() {
                });
    }

    @Override
    public AppautoEnvProfileView findEnvProfile(long id) {
        return call(HttpMethod.GET, BASE + "/env-profiles/" + id, null,
                new ParameterizedTypeReference<Response<AppautoEnvProfileView>>() {
                });
    }

    @Override
    public List<AppautoEnvProfileView> listEnvProfiles(long projectId, String profileKind) {
        StringBuilder uri = new StringBuilder(BASE + "/env-profiles?projectId=").append(projectId);
        if (profileKind != null && !profileKind.isBlank()) {
            uri.append("&profileKind=")
                    .append(java.net.URLEncoder.encode(profileKind,
                            java.nio.charset.StandardCharsets.UTF_8));
        }
        return call(HttpMethod.GET, uri.toString(), null,
                new ParameterizedTypeReference<Response<List<AppautoEnvProfileView>>>() {
                });
    }

    @Override
    public void deleteEnvProfile(long id) {
        call(HttpMethod.DELETE, BASE + "/env-profiles/" + id, null,
                new ParameterizedTypeReference<Response<Void>>() {
                });
    }

    @Override
    public List<Map<String, Object>> listAiSamples(long projectId, long caseId, int limit) {
        return call(HttpMethod.GET, BASE + "/ai-samples?projectId=" + projectId
                + "&caseId=" + caseId + "&limit=" + limit, null,
                new ParameterizedTypeReference<Response<List<Map<String, Object>>>>() {
                });
    }

    @Override
    public Map<String, Object> aiSampleHitRate(long projectId, long caseId) {
        return call(HttpMethod.GET, BASE + "/ai-samples/hit-rate?projectId=" + projectId
                + "&caseId=" + caseId, null,
                new ParameterizedTypeReference<Response<Map<String, Object>>>() {
                });
    }

    @Override
    public List<Map<String, Object>> listStabilityRows(long projectId, long caseId) {
        return call(HttpMethod.GET, BASE + "/stability-rows?projectId=" + projectId
                + "&caseId=" + caseId, null,
                new ParameterizedTypeReference<Response<List<Map<String, Object>>>>() {
                });
    }

    @Override
    public List<AppautoStepSuggestionView> listStepSuggestions(long projectId, long caseId,
                                                                Integer status, int limit) {
        StringBuilder uri = new StringBuilder(BASE + "/step-suggestions?projectId=" + projectId
                + "&caseId=" + caseId + "&limit=" + limit);
        if (status != null) {
            uri.append("&status=").append(status);
        }
        return call(HttpMethod.GET, uri.toString(), null,
                new ParameterizedTypeReference<Response<List<AppautoStepSuggestionView>>>() {
                });
    }

    @Override
    public AppautoStepSuggestionView findStepSuggestion(long id) {
        return call(HttpMethod.GET, BASE + "/step-suggestions/" + id, null,
                new ParameterizedTypeReference<Response<AppautoStepSuggestionView>>() {
                });
    }

    @Override
    public AppautoStepSuggestionView adoptStepSuggestion(long id, Integer expectedScriptVersion) {
        String query = expectedScriptVersion == null ? ""
                : "?expectedScriptVersion=" + expectedScriptVersion;
        return call(HttpMethod.POST, BASE + "/step-suggestions/" + id + "/adopt" + query, null,
                new ParameterizedTypeReference<Response<AppautoStepSuggestionView>>() {
                });
    }

    @Override
    public void rejectStepSuggestion(long id) {
        call(HttpMethod.POST, BASE + "/step-suggestions/" + id + "/reject", null,
                new ParameterizedTypeReference<Response<Void>>() {
                });
    }

    @Override
    public AppautoNlStepView generateNlStep(long projectId, String engineCode, String description) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("projectId", projectId);
        body.put("engineCode", engineCode);
        body.put("description", description);
        return call(HttpMethod.POST, BASE + "/nl-step", body,
                new ParameterizedTypeReference<Response<AppautoNlStepView>>() {
                });
    }

    @Override
    public AppautoRecordFlowView generateRecordFlow(long taskId, String engineCode,
                                                    String eventSource, String title,
                                                    List<Map<String, Object>> events) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("taskId", taskId);
        body.put("engineCode", engineCode);
        body.put("eventSource", eventSource);
        body.put("title", title);
        body.put("events", events);
        return call(HttpMethod.POST, BASE + "/record-flow", body,
                new ParameterizedTypeReference<Response<AppautoRecordFlowView>>() {
                });
    }

    @Override
    public List<AppautoRecordDraftView> listRecordDrafts(long caseId, Integer status, int limit) {
        StringBuilder uri = new StringBuilder(BASE + "/record-drafts?caseId=")
                .append(caseId).append("&limit=").append(limit);
        if (status != null) {
            uri.append("&status=").append(status);
        }
        return call(HttpMethod.GET, uri.toString(), null,
                new ParameterizedTypeReference<Response<List<AppautoRecordDraftView>>>() {
                });
    }

    @Override
    public AppautoRecordDraftView findRecordDraft(long id) {
        return call(HttpMethod.GET, BASE + "/record-drafts/" + id, null,
                new ParameterizedTypeReference<Response<AppautoRecordDraftView>>() {
                });
    }

    @Override
    public AppautoRecordDraftView confirmRecordDraft(long id, List<Integer> checkedStepNos,
                                                     Integer expectedScriptVersion) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("checkedStepNos", checkedStepNos);
        body.put("expectedScriptVersion", expectedScriptVersion);
        return call(HttpMethod.POST, BASE + "/record-drafts/" + id + "/confirm", body,
                new ParameterizedTypeReference<Response<AppautoRecordDraftView>>() {
                });
    }

    @Override
    public void rejectRecordDraft(long id) {
        call(HttpMethod.POST, BASE + "/record-drafts/" + id + "/reject", null,
                new ParameterizedTypeReference<Response<Void>>() {
                });
    }

    @Override
    public Long learnPopupRule(long taskId) {
        return call(HttpMethod.POST, BASE + "/popup-rules/learn?taskId=" + taskId, null,
                new ParameterizedTypeReference<Response<Long>>() {
                });
    }

    @Override
    public List<AppautoPopupRuleView> listPopupRules(long projectId, Integer status, int limit) {
        StringBuilder uri = new StringBuilder(BASE + "/popup-rules?projectId=")
                .append(projectId).append("&limit=").append(limit);
        if (status != null) {
            uri.append("&status=").append(status);
        }
        return call(HttpMethod.GET, uri.toString(), null,
                new ParameterizedTypeReference<Response<List<AppautoPopupRuleView>>>() {
                });
    }

    @Override
    public AppautoPopupRuleView findPopupRule(long id) {
        return call(HttpMethod.GET, BASE + "/popup-rules/" + id, null,
                new ParameterizedTypeReference<Response<AppautoPopupRuleView>>() {
                });
    }

    @Override
    public AppautoPopupRuleView confirmPopupRule(long id) {
        return call(HttpMethod.POST, BASE + "/popup-rules/" + id + "/confirm", null,
                new ParameterizedTypeReference<Response<AppautoPopupRuleView>>() {
                });
    }

    @Override
    public void rejectPopupRule(long id) {
        call(HttpMethod.POST, BASE + "/popup-rules/" + id + "/reject", null,
                new ParameterizedTypeReference<Response<Void>>() {
                });
    }

    @Override
    public AppautoElementPageView pageElements(long projectId, String keyword, String locatorType,
                                               int page, int pageSize) {
        StringBuilder uri = new StringBuilder(BASE + "/elements?projectId=").append(projectId)
                .append("&page=").append(page).append("&pageSize=").append(pageSize);
        if (keyword != null && !keyword.isBlank()) {
            uri.append("&keyword=").append(java.net.URLEncoder.encode(keyword,
                    java.nio.charset.StandardCharsets.UTF_8));
        }
        if (locatorType != null && !locatorType.isBlank()) {
            uri.append("&locatorType=").append(java.net.URLEncoder.encode(locatorType,
                    java.nio.charset.StandardCharsets.UTF_8));
        }
        return call(HttpMethod.GET, uri.toString(), null,
                new ParameterizedTypeReference<Response<AppautoElementPageView>>() {
                });
    }

    @Override
    public AppautoElementView findElement(long id) {
        return call(HttpMethod.GET, BASE + "/elements/" + id, null,
                new ParameterizedTypeReference<Response<AppautoElementView>>() {
                });
    }

    @Override
    public long createElement(long projectId, AppautoElementInput input) {
        Long id = call(HttpMethod.POST, BASE + "/elements?projectId=" + projectId, input,
                new ParameterizedTypeReference<Response<Long>>() {
                });
        return id == null ? 0L : id;
    }

    @Override
    public AppautoElementView updateElement(long id, AppautoElementInput input) {
        return call(HttpMethod.PUT, BASE + "/elements/" + id, input,
                new ParameterizedTypeReference<Response<AppautoElementView>>() {
                });
    }

    @Override
    public void deleteElement(long id) {
        call(HttpMethod.DELETE, BASE + "/elements/" + id, null,
                new ParameterizedTypeReference<Response<Void>>() {
                });
    }

    @Override
    public long captureElement(long projectId, AppautoElementCaptureInput input) {
        Long id = call(HttpMethod.POST, BASE + "/elements/capture?projectId=" + projectId, input,
                new ParameterizedTypeReference<Response<Long>>() {
                });
        return id == null ? 0L : id;
    }

    @Override
    public AppautoWxMockView generateWxMock(long projectId, String apiPath, String method,
                                            Map<String, Object> contract) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("projectId", projectId);
        body.put("apiPath", apiPath);
        body.put("method", method);
        body.put("contract", contract);
        return call(HttpMethod.POST, BASE + "/wx-mock", body,
                new ParameterizedTypeReference<Response<AppautoWxMockView>>() {
                });
    }

    @Override
    public List<AppautoSupportView> engineSupport(long projectId) {
        return call(HttpMethod.GET, BASE + "/engine-support?projectId=" + projectId, null,
                new ParameterizedTypeReference<Response<List<AppautoSupportView>>>() {
                });
    }

    @Override
    public AppautoUpgradeAdviceView upgradeAdvice(long projectId, String manifestVersion) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("projectId", projectId);
        body.put("manifestVersion", manifestVersion);
        return call(HttpMethod.POST, BASE + "/upgrade-advice", body,
                new ParameterizedTypeReference<Response<AppautoUpgradeAdviceView>>() {
                });
    }

    @Override
    public List<AppautoUpgradeWindowView> upgradeWindow() {
        return call(HttpMethod.GET, BASE + "/upgrade-window", null,
                new ParameterizedTypeReference<Response<List<AppautoUpgradeWindowView>>>() {
                });
    }

    @Override
    public List<AppautoUpgradeFlakyLinkView> upgradeFlakyLinks() {
        return call(HttpMethod.GET, BASE + "/upgrade-flaky-links", null,
                new ParameterizedTypeReference<Response<List<AppautoUpgradeFlakyLinkView>>>() {
                });
    }

    @Override
    public AppautoMatrixPrecheckView matrixPrecheck(AppautoMatrixFanoutRequest request) {
        return call(HttpMethod.POST, BASE + "/matrix-precheck", request,
                new ParameterizedTypeReference<Response<AppautoMatrixPrecheckView>>() {
                });
    }

    @Override
    public AppautoMatrixDispatchResult matrixFanout(AppautoMatrixFanoutRequest request) {
        return call(HttpMethod.POST, BASE + "/matrix-fanout", request,
                new ParameterizedTypeReference<Response<AppautoMatrixDispatchResult>>() {
                });
    }

    @Override
    public List<AppautoMatrixModelRow> matrixModelReport(long projectId, long caseId) {
        return call(HttpMethod.GET, BASE + "/matrix-model-report?projectId=" + projectId
                + "&caseId=" + caseId, null,
                new ParameterizedTypeReference<Response<List<AppautoMatrixModelRow>>>() {
                });
    }

    @Override
    public AppautoMatrixSampleView matrixSample(AppautoMatrixSampleRequest request) {
        return call(HttpMethod.POST, BASE + "/matrix-sample", request,
                new ParameterizedTypeReference<Response<AppautoMatrixSampleView>>() {
                });
    }

    @Override
    public long createAppPackage(AppPackageSaveRequest request) {
        Long id = call(HttpMethod.POST,
                BASE + "/apps?projectId=" + request.projectId(), request,
                new ParameterizedTypeReference<Response<Long>>() {
                });
        return id == null ? 0L : id;
    }

    @Override
    public AppautoAppView findAppPackage(long id) {
        return call(HttpMethod.GET, BASE + "/apps/" + id, null,
                new ParameterizedTypeReference<Response<AppautoAppView>>() {
                });
    }

    @Override
    public List<AppautoAppView> listAppPackages(long projectId) {
        return call(HttpMethod.GET, BASE + "/apps?projectId=" + projectId, null,
                new ParameterizedTypeReference<Response<List<AppautoAppView>>>() {
                });
    }

    @Override
    public String uploadAppPackage(long id, String filename, String contentType, byte[] content) {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        MediaType partType = contentType == null || contentType.isBlank()
                ? MediaType.APPLICATION_OCTET_STREAM
                : MediaType.parseMediaType(contentType);
        body.part("file", content, partType).filename(filename);
        return RestClientInvoker.callMultipart(uploadRest, BASE + "/apps/" + id + "/package", body,
                new ParameterizedTypeReference<Response<String>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public void deleteAppPackage(long id) {
        call(HttpMethod.DELETE, BASE + "/apps/" + id, null,
                new ParameterizedTypeReference<Response<Void>>() {
                });
    }


    @Override
    public AppautoTakeoverView requestTakeover(long deviceId, Long taskId, String reason,
                                               long requesterId) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("deviceId", deviceId);
        body.put("taskId", taskId);
        body.put("reason", reason);
        body.put("requesterId", requesterId);
        return call(HttpMethod.POST, BASE + "/takeover/request", body,
                new ParameterizedTypeReference<Response<AppautoTakeoverView>>() {
                });
    }

    @Override
    public AppautoTakeoverView agreeTakeover(long requestId, long deciderId) {
        return call(HttpMethod.POST, BASE + "/takeover/" + requestId + "/agree?deciderId="
                + deciderId, null,
                new ParameterizedTypeReference<Response<AppautoTakeoverView>>() {
                });
    }

    @Override
    public AppautoTakeoverView rejectTakeover(long requestId, long deciderId) {
        return call(HttpMethod.POST, BASE + "/takeover/" + requestId + "/reject?deciderId="
                + deciderId, null,
                new ParameterizedTypeReference<Response<AppautoTakeoverView>>() {
                });
    }

    @Override
    public AppautoTakeoverView forceTakeover(long requestId, long adminOperatorId) {
        return call(HttpMethod.POST, BASE + "/takeover/" + requestId + "/force?adminOperatorId="
                + adminOperatorId, null,
                new ParameterizedTypeReference<Response<AppautoTakeoverView>>() {
                });
    }

    @Override
    public AppautoTakeoverView endTakeover(long requestId, long operatorId) {
        return call(HttpMethod.POST, BASE + "/takeover/" + requestId + "/end?operatorId="
                + operatorId, null,
                new ParameterizedTypeReference<Response<AppautoTakeoverView>>() {
                });
    }

    @Override
    public AppautoTakeoverView findTakeover(long requestId) {
        return call(HttpMethod.GET, BASE + "/takeover/" + requestId, null,
                new ParameterizedTypeReference<Response<AppautoTakeoverView>>() {
                });
    }

    @Override
    public Map<String, Object> enqueueTakeoverInput(long requestId, long operatorId,
                                                    List<Map<String, Object>> commands) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("operatorId", operatorId);
        body.put("commands", commands);
        return call(HttpMethod.POST, BASE + "/takeover/input/enqueue", body,
                new ParameterizedTypeReference<Response<Map<String, Object>>>() {
                });
    }


    @Override
    public AppautoReservationView createReservation(ReservationCreateRequest request,
                                                    long holderUserId) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("projectId", request.projectId());
        body.put("deviceId", request.deviceId());
        body.put("batchId", request.batchId());
        body.put("holderUserId", holderUserId);
        body.put("startsAt", request.startsAt());
        body.put("endsAt", request.endsAt());
        body.put("reason", request.reason());
        return call(HttpMethod.POST, BASE + "/reservation", body,
                new ParameterizedTypeReference<Response<AppautoReservationView>>() {
                });
    }

    @Override
    public AppautoReservationView findReservation(long id) {
        return call(HttpMethod.GET, BASE + "/reservation/" + id, null,
                new ParameterizedTypeReference<Response<AppautoReservationView>>() {
                });
    }

    @Override
    public List<AppautoReservationView> listReservations(Long deviceId, Long projectId,
                                                         Long batchId, boolean includeTerminal) {
        StringBuilder uri = new StringBuilder(BASE + "/reservation?includeTerminal=")
                .append(includeTerminal);
        if (deviceId != null) {
            uri.append("&deviceId=").append(deviceId);
        }
        if (projectId != null) {
            uri.append("&projectId=").append(projectId);
        }
        if (batchId != null) {
            uri.append("&batchId=").append(batchId);
        }
        return call(HttpMethod.GET, uri.toString(), null,
                new ParameterizedTypeReference<Response<List<AppautoReservationView>>>() {
                });
    }

    @Override
    public AppautoReservationView bindReservationBatch(long id, Long batchId) {
        return call(HttpMethod.POST,
                BASE + "/reservation/" + id + "/bind-batch"
                        + (batchId == null ? "" : "?batchId=" + batchId), null,
                new ParameterizedTypeReference<Response<AppautoReservationView>>() {
                });
    }

    @Override
    public AppautoReservationView releaseReservation(long id, String reason) {
        return call(HttpMethod.POST,
                BASE + "/reservation/" + id + "/release" + reasonQuery(reason, "?"), null,
                new ParameterizedTypeReference<Response<AppautoReservationView>>() {
                });
    }

    @Override
    public AppautoReservationView cancelReservation(long id, String reason) {
        return call(HttpMethod.POST,
                BASE + "/reservation/" + id + "/cancel" + reasonQuery(reason, "?"), null,
                new ParameterizedTypeReference<Response<AppautoReservationView>>() {
                });
    }

    @Override
    public AppautoReservationView approveReservation(long id, long approverId) {
        return call(HttpMethod.POST,
                BASE + "/reservation/" + id + "/approve?approverUserId=" + approverId, null,
                new ParameterizedTypeReference<Response<AppautoReservationView>>() {
                });
    }

    @Override
    public AppautoReservationView rejectReservation(long id, long approverId, String reason) {
        return call(HttpMethod.POST,
                BASE + "/reservation/" + id + "/reject?approverUserId=" + approverId
                        + reasonQuery(reason, "&"), null,
                new ParameterizedTypeReference<Response<AppautoReservationView>>() {
                });
    }

    @Override
    public int sweepReservations() {
        Integer swept = call(HttpMethod.POST, BASE + "/reservation/sweep", null,
                new ParameterizedTypeReference<Response<Integer>>() {
                });
        return swept == null ? 0 : swept;
    }

    
    private static String reasonQuery(String reason, String connector) {
        if (reason == null || reason.isBlank()) {
            return "";
        }
        return connector + "reason=" + java.net.URLEncoder.encode(reason,
                java.nio.charset.StandardCharsets.UTF_8);
    }

    private <T> T call(HttpMethod method, String uri, Object body,
                       ParameterizedTypeReference<Response<T>> type) {
        return RestClientInvoker.call(rest, method, uri, body, type, UserContext.currentUserId());
    }
}
