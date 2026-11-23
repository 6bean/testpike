package com.sixbean.testpike.admin.appauto.infrastructure.remote.client;

import com.sixbean.testpike.admin.appauto.api.dto.AppautoAppView;
import com.sixbean.testpike.admin.appauto.api.dto.AppautoDeviceView;
import com.sixbean.testpike.admin.appauto.api.dto.AppautoElementCaptureInput;
import com.sixbean.testpike.admin.appauto.api.dto.AppautoElementInput;
import com.sixbean.testpike.admin.appauto.api.dto.AppautoElementPageView;
import com.sixbean.testpike.admin.appauto.api.dto.AppautoElementView;
import com.sixbean.testpike.admin.appauto.api.dto.AppautoEngineAvailabilityView;
import com.sixbean.testpike.admin.appauto.api.dto.AppautoEngineHealthView;
import com.sixbean.testpike.admin.appauto.api.dto.AppautoEngineView;
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

import java.util.List;
import java.util.Map;


public interface CoreAppautoClient {

    long registerDevice(DeviceSaveRequest request);

    void updateDevice(long id, DeviceSaveRequest request);

    
    AppautoDeviceView findDevice(long id);

    /**
     * Device-wall thumbnail cell for one device (P1 wall live face; honest
     * IDLE/NO_FRAME cells are normal payloads, only unknown device is an error).
     */
    com.sixbean.testpike.admin.appauto.api.dto.AppautoDeviceThumbnailView
            deviceThumbnail(long deviceId);

    List<AppautoDeviceView> listDevices(long projectId, String label);

    void unquarantineDevice(long id);

    void deleteDevice(long id);

 
    List<AppautoLeaseRunView> listDeviceLeaseRuns(long deviceId);

    
    List<AppautoEngineView> listEngines(boolean includeDisabled);

 
    List<AppautoEngineAvailabilityView> listEngineAvailability(boolean includeDisabled);

 
    List<AppautoEngineHealthView> listEngineHealth(boolean includeDisabled);

 
    AppautoPortabilityView precheckPortability(long projectId, long caseId, String engineCode);

    
    AppautoEngineView findEngine(String code);

    
    List<AppautoPlatformView> listPlatforms(boolean includeDisabled);

    
    AppautoPlatformView findPlatform(String code);

 
    void updateEngine(String code, EngineManageRequest request);

    
    void updatePlatform(String code, PlatformManageRequest request);

    
    long createEnvProfile(long projectId, EnvProfileSaveRequest request);

    
    void updateEnvProfile(long id, EnvProfileSaveRequest request);

    
    AppautoEnvProfileView findEnvProfile(long id);

    
    List<AppautoEnvProfileView> listEnvProfiles(long projectId, String profileKind);

    
    void deleteEnvProfile(long id);

 
    List<Map<String, Object>> listAiSamples(long projectId, long caseId, int limit);

    
    Map<String, Object> aiSampleHitRate(long projectId, long caseId);

    
    List<Map<String, Object>> listStabilityRows(long projectId, long caseId);

    
    List<AppautoStepSuggestionView> listStepSuggestions(long projectId, long caseId,
                                                        Integer status, int limit);

    
    AppautoStepSuggestionView findStepSuggestion(long id);

    
    AppautoStepSuggestionView adoptStepSuggestion(long id, Integer expectedScriptVersion);

    
    void rejectStepSuggestion(long id);

 
    AppautoNlStepView generateNlStep(long projectId, String engineCode, String description);

 
    AppautoRecordFlowView generateRecordFlow(long taskId, String engineCode, String eventSource,
                                             String title,
                                             List<Map<String, Object>> events);

    
    List<AppautoRecordDraftView> listRecordDrafts(long caseId, Integer status, int limit);

    
    AppautoRecordDraftView findRecordDraft(long id);

    
    AppautoRecordDraftView confirmRecordDraft(long id, List<Integer> checkedStepNos,
                                              Integer expectedScriptVersion);

    
    void rejectRecordDraft(long id);

    
    Long learnPopupRule(long taskId);

    
    List<AppautoPopupRuleView> listPopupRules(long projectId, Integer status, int limit);

    
    AppautoPopupRuleView findPopupRule(long id);

    
    AppautoPopupRuleView confirmPopupRule(long id);

    
    void rejectPopupRule(long id);

 
    AppautoWxMockView generateWxMock(long projectId, String apiPath, String method,
                                     Map<String, Object> contract);

 
    List<AppautoSupportView> engineSupport(long projectId);

 
    AppautoUpgradeAdviceView upgradeAdvice(long projectId, String manifestVersion);

 
    List<AppautoUpgradeWindowView> upgradeWindow();

 
    List<AppautoUpgradeFlakyLinkView> upgradeFlakyLinks();

 
    AppautoMatrixPrecheckView matrixPrecheck(AppautoMatrixFanoutRequest request);

 
    AppautoMatrixDispatchResult matrixFanout(AppautoMatrixFanoutRequest request);

 
    List<AppautoMatrixModelRow> matrixModelReport(long projectId, long caseId);

 
    AppautoMatrixSampleView matrixSample(AppautoMatrixSampleRequest request);

 
    long createAppPackage(AppPackageSaveRequest request);

    
    AppautoAppView findAppPackage(long id);

    
    List<AppautoAppView> listAppPackages(long projectId);

    
    String uploadAppPackage(long id, String filename, String contentType, byte[] content);

    
    void deleteAppPackage(long id);


    
    AppautoTakeoverView requestTakeover(long deviceId, Long taskId, String reason, long requesterId);

    
    AppautoTakeoverView agreeTakeover(long requestId, long deciderId);

    
    AppautoTakeoverView rejectTakeover(long requestId, long deciderId);

    
    AppautoTakeoverView forceTakeover(long requestId, long adminOperatorId);

    
    AppautoTakeoverView endTakeover(long requestId, long operatorId);

    
    AppautoTakeoverView findTakeover(long requestId);

    
    Map<String, Object> enqueueTakeoverInput(long requestId, long operatorId,
                                             List<Map<String, Object>> commands);


    
    AppautoReservationView createReservation(ReservationCreateRequest request, long holderUserId);

    
    AppautoReservationView findReservation(long id);

    
    List<AppautoReservationView> listReservations(Long deviceId, Long projectId, Long batchId,
                                                  boolean includeTerminal);

 
    AppautoReservationView bindReservationBatch(long id, Long batchId);

    
    AppautoReservationView releaseReservation(long id, String reason);

    
    AppautoReservationView cancelReservation(long id, String reason);

    
    AppautoReservationView approveReservation(long id, long approverId);

    
    AppautoReservationView rejectReservation(long id, long approverId, String reason);

    
    int sweepReservations();

    /** Paged element-library listing (page/pageSize contract; keyword and type filters resolved core-side). */
    AppautoElementPageView pageElements(long projectId, String keyword, String locatorType,
                                         int page, int pageSize);

    /** Single element read (missing row = core human-readable 404 passthrough; admin gates on the echoed owner). */
    AppautoElementView findElement(long id);

    /** Create one element profile (duplicate-name conflict wording is core-owned; returns the new row id). */
    long createElement(long projectId, AppautoElementInput input);

    /** Full update of one element profile (ownership gate resolves from core first; field validation stays core-side). */
    AppautoElementView updateElement(long id, AppautoElementInput input);

    /** Delete one element profile (core validation and conflict wording travel unmutated). */
    void deleteElement(long id);

    /** File a live-frame crop as a new image element profile; returns the new id. */
    long captureElement(long projectId, AppautoElementCaptureInput input);
}
