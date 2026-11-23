package com.sixbean.testpike.admin.judge.api.controller;

import com.sixbean.testpike.admin.ai.api.dto.DocumentInfoView;
import com.sixbean.testpike.admin.ai.infrastructure.remote.client.CoreDocumentClient;
import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.judge.api.dto.EvalSetPublishBody;
import com.sixbean.testpike.admin.judge.api.dto.EvalSetAdoptedRaiseBody;
import com.sixbean.testpike.admin.judge.api.dto.EvalSetBatchRaiseBody;
import com.sixbean.testpike.admin.judge.api.dto.EvalSetPublishRequest;
import com.sixbean.testpike.admin.judge.api.dto.EvalSetRaiseBody;
import com.sixbean.testpike.admin.judge.api.dto.EvalSampleView;
import com.sixbean.testpike.admin.judge.api.dto.EvalSetVersionOptionView;
import com.sixbean.testpike.admin.judge.api.dto.EvalSetVersionView;
import com.sixbean.testpike.admin.judge.api.dto.QaGenEventView;
import com.sixbean.testpike.admin.judge.api.dto.QaGenJobView;
import com.sixbean.testpike.admin.judge.api.dto.QaGenRunView;
import com.sixbean.testpike.admin.judge.api.dto.QaGenSubmitView;
import com.sixbean.testpike.admin.judge.infrastructure.remote.client.CoreJudgeClient;
import com.sixbean.testpike.admin.judge.infrastructure.remote.client.JudgeAiClient;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * <p>Permission doors are dedicated judge keys (AW batch , correcting
 * the M1 borrowed-key situation): the qa lanes ride perm:judge:qagen:run and
 * publish rides perm:judge:evalset:manage.
 * <p>Job plane : the preview door rides the submit receipt (two
 * shapes — inline sync within the group cap, accepted run token beyond it),
 * and the two read doors relay the job ledger plus the process-event cursor
 * window so an over-cap document is watched, never hung. Read doors guard the
 * document's own project (same ownership segment as the extraction tail door).
 */
@RestController
@RequestMapping("/api/v1/eval-sets")
public class EvalSetController {

    private final CoreJudgeClient coreClient;
    private final JudgeAiClient aiClient;
    private final CoreDocumentClient documentClient;
    private final ProjectAccessGuard guard;

    public EvalSetController(CoreJudgeClient coreClient, JudgeAiClient aiClient,
                             CoreDocumentClient documentClient, ProjectAccessGuard guard) {
        this.coreClient = coreClient;
        this.aiClient = aiClient;
        this.documentClient = documentClient;
        this.guard = guard;
    }

    /** Submit door: inline sync preview within the group cap, accepted job receipt beyond it (single decision point = ai-service). */
    @RequirePermission("perm:judge:qagen:run")
    @PostMapping("/qa-preview")
    public Response<QaGenSubmitView> previewQuestions(@RequestParam Long projectId,
                                                      @RequestParam Long documentId,
                                                      @RequestParam(required = false) String modelHint) {
        return Response.builderSuccess(aiClient.submit(
                projectId, documentId, modelHint, UserContext.currentUserId()));
    }

    /** Latest job row (status + cumulative groups/items) of the document. */
    @RequirePermission("perm:judge:qagen:run")
    @GetMapping("/qa-gen-job")
    public Response<QaGenJobView> qaGenJob(@RequestParam Long documentId) {
        CurrentUser user = UserContext.current();
        DocumentInfoView doc = documentClient.get(documentId);
        guard.assertCanView(user, doc.projectId());
        return Response.builderSuccess(aiClient.jobStatus(documentId, user.userId()));
    }

    /** Cursor window of the run's process events (replay-idempotent tail face). */
    @RequirePermission("perm:judge:qagen:run")
    @GetMapping("/qa-gen-events")
    public Response<List<QaGenEventView>> qaGenEvents(@RequestParam Long documentId,
                                                      @RequestParam String runToken,
                                                      @RequestParam(defaultValue = "0") long cursor,
                                                      @RequestParam(required = false) Integer limit) {
        CurrentUser user = UserContext.current();
        DocumentInfoView doc = documentClient.get(documentId);
        guard.assertCanView(user, doc.projectId());
        return Response.builderSuccess(
                aiClient.jobEvents(documentId, runToken, cursor, limit, user.userId()));
    }

    /**
 * Revisit door of the tail window : the historical
 * question-generation runs of one document, newest first. Ownership rides
 * the document's own project segment like the other read doors; a null
 * limit defers to the ai-service hot key so no second default exists here.
 */
    @RequirePermission("perm:judge:qagen:run")
    @GetMapping("/qa-gen-runs")
    public Response<List<QaGenRunView>> qaGenRuns(@RequestParam Long documentId,
                                                  @RequestParam(required = false) Integer limit) {
        CurrentUser user = UserContext.current();
        DocumentInfoView doc = documentClient.get(documentId);
        guard.assertCanView(user, doc.projectId());
        return Response.builderSuccess(aiClient.qaGenRuns(documentId, limit, user.userId()));
    }

    
    @RequirePermission("perm:judge:evalset:manage")
    @PostMapping("/publish")
    public Response<EvalSetVersionView> publish(@RequestBody EvalSetPublishRequest request) {
        EvalSetPublishBody body = new EvalSetPublishBody(
                request.setName(), request.samples(), UserContext.currentUserId());
        return Response.builderSuccess(coreClient.publish(body));
    }

    /**
     * Evaluation-set version sample list (AW1 read door; direct forward of
     * core's samples GET — version/anchor decisions stay single-source in
     * core, this bridge rethrows core rejections with the same code).
     */
    @RequirePermission("perm:judge:evalset:view")
    @GetMapping("/samples")
    public Response<List<EvalSampleView>> samples(@RequestParam String setName,
                                                  @RequestParam int version) {
        return Response.builderSuccess(coreClient.samples(setName, version));
    }

    /**
 * Published evaluation-set version list ( segment A picker door; the
 * version segments are immutable — core enumerates, this bridge forwards
 * the rows untouched so the run form can pin the anchor triple).
 */
    @RequirePermission("perm:judge:evalset:view")
    @GetMapping("/evalsets")
    public Response<List<EvalSetVersionOptionView>> evalsets() {
        return Response.builderSuccess(coreClient.listEvalsetVersions());
    }

    
    @RequirePermission("perm:judge:evalset:manage")
    @PostMapping("/raise")
    public Response<EvalSetVersionView> raise(@RequestBody EvalSetRaiseBody request) {
        return Response.builderSuccess(coreClient.raise(
                request.setName(), request.sampleIds(), UserContext.currentUserId()));
    }

    
    @RequirePermission("perm:judge:evalset:manage")
    @PostMapping("/raise-from-batch")
    public Response<EvalSetVersionView> raiseFromBatch(
            @RequestBody EvalSetBatchRaiseBody request) {
        return Response.builderSuccess(coreClient.raiseFromBatch(
                request.setName(), request.taskIds(), UserContext.currentUserId()));
    }

    
    @RequirePermission("perm:judge:evalset:manage")
    @PostMapping("/raise-from-adopted")
    public Response<EvalSetVersionView> raiseFromAdopted(
            @RequestBody EvalSetAdoptedRaiseBody request) {
        return Response.builderSuccess(coreClient.raiseFromAdopted(
                request.setName(), request.caseIds(), UserContext.currentUserId()));
    }
}
