package com.sixbean.testpike.aiservice.judge.api.controller;

import com.sixbean.testpike.aiservice.judge.api.dto.QaGenJobView;
import com.sixbean.testpike.aiservice.judge.api.dto.QaGenRequest;
import com.sixbean.testpike.aiservice.judge.api.dto.QaGenRunView;
import com.sixbean.testpike.aiservice.judge.api.dto.QaGenSubmitView;
import com.sixbean.testpike.aiservice.judge.domain.service.EvalQuestionGenService;
import com.sixbean.testpike.aiservice.taskstream.domain.component.TaskProcessEvent;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * <p>Job plane : the submit face answers either an
 * inline preview or an accepted receipt, and the two polling faces stream the
 * run's progress. Mirrors the spec-preview door's accept-then-poll shape.
 */
@RestController
@RequestMapping("/internal/judge/evalset")
public class EvalQuestionGenInternalController {

    private final EvalQuestionGenService evalQuestionGenService;

    public EvalQuestionGenInternalController(EvalQuestionGenService evalQuestionGenService) {
        this.evalQuestionGenService = evalQuestionGenService;
    }

    /** Submit face: inline preview within the group cap, accepted job beyond it. */
    @PostMapping("/qa-gen-submit")
    public Response<QaGenSubmitView> submit(@RequestBody QaGenRequest request) {
        return Response.builderSuccess(evalQuestionGenService.submit(request));
    }

    /** Latest job row of the document (status + cumulative group/item face). */
    @GetMapping("/qa-gen-job")
    public Response<QaGenJobView> jobStatus(@RequestParam("documentId") long documentId) {
        return Response.builderSuccess(evalQuestionGenService.jobStatus(documentId));
    }

    /** Cursor window of the run's process events, replay-idempotent by row id. */
    @GetMapping("/qa-gen-events")
    public Response<List<TaskProcessEvent>> jobEvents(
            @RequestParam("documentId") long documentId,
            @RequestParam("runToken") String runToken,
            @RequestParam(value = "cursor", defaultValue = "0") long cursor,
            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        return Response.builderSuccess(
                evalQuestionGenService.jobEvents(documentId, runToken, cursor, limit));
    }

    /**
     * Run history of the document's question-generation door, newest first —
     * the resident revisit entry of the tail window (every past run stays
     * replayable through the events door while its frames survive retention).
     */
    @GetMapping("/qa-gen-runs")
    public Response<List<QaGenRunView>> qaGenRuns(
            @RequestParam("documentId") long documentId,
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        return Response.builderSuccess(evalQuestionGenService.qaGenRuns(documentId, limit));
    }
}
