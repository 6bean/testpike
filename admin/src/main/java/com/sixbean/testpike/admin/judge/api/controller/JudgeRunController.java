package com.sixbean.testpike.admin.judge.api.controller;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.judge.api.dto.JudgeDispatchReceiptView;
import com.sixbean.testpike.admin.judge.api.dto.JudgeRubricOptionView;
import com.sixbean.testpike.admin.judge.api.dto.JudgeRunCreateBody;
import com.sixbean.testpike.admin.judge.api.dto.JudgeRunDispatchBody;
import com.sixbean.testpike.admin.judge.api.dto.JudgeRunPageView;
import com.sixbean.testpike.admin.judge.api.dto.JudgeRunView;
import com.sixbean.testpike.admin.judge.infrastructure.remote.client.CoreJudgeClient;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.clientobject.Response;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Evaluation-run facade (AW4, admin bridge).
 * <p>Three doors over the core judge run plane: run-history page (read,
 * perm:judge:run:view), create run (perm:judge:run:create — both anchors are
 * frozen at creation and every rejection reason is core's single-source
 * decision) and dispatch (perm:judge:run:create — the four dispatch gates
 * and per-sample enqueue live in core JudgeDispatchService). The app layer
 * composes and forwards only: createdBy is always the current session user
 * (never the client-self-reported value), core plain-language rejections are
 * rethrown with the same code, zero second validation in this bridge (red
 * line: judge decision logic stays single-source in core).
 */
@RestController
@RequestMapping("/api/v1/judge-runs")
public class JudgeRunController {

    private final CoreJudgeClient coreClient;

    public JudgeRunController(CoreJudgeClient coreClient) {
        this.coreClient = coreClient;
    }

    /** Run history page (newest first; filters optional — set name and status). */
    @RequirePermission("perm:judge:run:view")
    @GetMapping
    public Response<JudgeRunPageView> list(
            @RequestParam(required = false) String evalsetName,
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Response.builderSuccess(coreClient.listRuns(evalsetName, status, page, size));
    }

    /**
 * Rubric family option list for the run-creation form ( segment A
 * picker door; latest version per name x domain — the versioning decision
 * stays in core, this bridge echoes the rows untouched).
 */
    @RequirePermission("perm:judge:run:view")
    @GetMapping("/rubrics")
    public Response<List<JudgeRubricOptionView>> rubrics() {
        return Response.builderSuccess(coreClient.listRubricFamilies());
    }

    /** Create a run (anchors frozen; gates single-source in core, session identity stamped). */
    @RequirePermission("perm:judge:run:create")
    @PostMapping
    public Response<JudgeRunView> create(@RequestBody JudgeRunCreateBody request) {
        JudgeRunCreateBody stamped = new JudgeRunCreateBody(
                request.rubricId(), request.evalsetName(), request.evalsetVersion(),
                request.evalsetDigest(), request.targetType(), request.targetRef(),
                request.llmConfigId(), UserContext.currentUserId());
        return Response.builderSuccess(coreClient.createRun(stamped));
    }

    /** Dispatch a pending run into the judge queue (receipt echoed untouched). */
    @RequirePermission("perm:judge:run:create")
    @PostMapping("/{runId}/dispatch")
    public Response<JudgeDispatchReceiptView> dispatch(
            @PathVariable long runId,
            @RequestBody(required = false) JudgeRunDispatchBody request) {
        CurrentUser user = UserContext.current();
        return Response.builderSuccess(coreClient.dispatch(
                runId, request == null ? null : request.evaluatedProvider(), user.userId()));
    }
}
