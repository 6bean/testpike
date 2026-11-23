package com.sixbean.testpike.core.ai.api.controller;

import com.sixbean.testpike.core.ai.api.dto.AiTaskCreateInput;
import com.sixbean.testpike.core.ai.api.dto.AiTaskInfo;
import com.sixbean.testpike.core.ai.domain.service.AiTaskService;
import com.sixbean.testpike.core.audit.domain.component.OperatorContext;
import com.sixbean.testpike.common.mq.AiGenMessage;
import com.sixbean.testpike.core.events.domain.service.AiGenPublisher;

import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Internal interface for AI generation tasks in the core service layer, used by admin to create and query tasks and by ai-service to write back status.
 * Flow: admin POST /api/v1/ai/generate triggers core to create a pending task and publish ai.case.generate to MQ.
 * ai-service consumes the message and uses this interface to write back running, done, or failed status.
 * Operator is passed through from X-Operator-Id to close the audit created_by loop.
 *
 */
@RestController
@RequestMapping("/internal/ai-tasks")
public class AiTaskInternalController {

    private final AiTaskService aiTaskService;
    private final AiGenPublisher aiGenPublisher;

    public AiTaskInternalController(AiTaskService aiTaskService, AiGenPublisher aiGenPublisher) {
        this.aiTaskService = aiTaskService;
        this.aiGenPublisher = aiGenPublisher;
    }

 /** Create a generation task by enqueuing it as pending, publishing ai.case.generate or ai.script.generate to MQ, and returning taskId. */
    @PostMapping
    public Response<Map<String, Long>> create(@RequestBody AiTaskCreateInput input,
                                              @RequestHeader(value = "X-Operator-Id", required = false) Long operatorId) {
        long operator = operatorId != null ? operatorId
                : OperatorContext.currentOperatorId() != null ? OperatorContext.currentOperatorId() : 0L;
        long taskId = aiTaskService.create(new AiTaskCreateInput(
                input.projectId(), operator, input.taskType(),
                input.requirementText(), input.requirementId(), input.caseId(), null));
        aiGenPublisher.publish(new AiGenMessage(taskId, input.projectId(), operator, input.taskType(),
                input.requirementText(), input.requirementId(), input.caseId()));
        return Response.builderSuccess(Map.of("taskId", taskId));
    }

    /** Query a task via GET /ai/tasks/{taskId}. */
    @GetMapping("/{taskId}")
    public Response<AiTaskInfo> get(@PathVariable long taskId) {
        return Response.builderSuccess(aiTaskService.get(taskId));
    }

    /**
 * S4 segment B: cursor window of the script-generation run's process
 * events (cursor = last row id the viewer saw; an absent limit defers to
 * the hot key ai.stream.chunk-size, clamped 10~1000). Read-only
 * observation face — the project-ownership gate sits on the admin
 * controller, same posture as the extraction tail.
 */
    @GetMapping("/{taskId}/process-events")
    public Response<List<com.sixbean.testpike.core.events.api.dto.TaskEventRow>> processEvents(
            @PathVariable long taskId,
            @RequestParam(defaultValue = "0") long cursor,
            @RequestParam(required = false) Integer limit) {
        return Response.builderSuccess(aiTaskService.processEvents(taskId, cursor, limit));
    }

    /** Write back the running status after ai-service consumes the task. */
    @PostMapping("/{taskId}/running")
    public Response<Void> markRunning(@PathVariable long taskId) {
        aiTaskService.markRunning(taskId);
        return Response.builderSuccess(null);
    }

    /** Write back done and the result case ID after ai-service generation succeeds. */
    @PostMapping("/{taskId}/done")
    public Response<Void> markDone(@PathVariable long taskId,
                                   @RequestBody(required = false) Map<String, Long> body) {
        Long caseId = body == null ? null : body.get("caseId");
        aiTaskService.markDone(taskId, caseId);
        return Response.builderSuccess(null);
    }

    /** Write back failed with an error category and human-readable reason after ai-service generation fails. */
    @PostMapping("/{taskId}/failed")
    public Response<Void> markFailed(@PathVariable long taskId,
                                     @RequestBody Map<String, String> body) {
        aiTaskService.markFailed(taskId, body.get("errorCode"), body.get("errorMsg"));
        return Response.builderSuccess(null);
    }

 /** Backfill the model identifier after ai-service routes by project and records the model used. */
    @PostMapping("/{taskId}/model")
    public Response<Void> markModel(@PathVariable long taskId,
                                    @RequestBody Map<String, String> body) {
        aiTaskService.markModel(taskId, body.get("modelName"));
        return Response.builderSuccess(null);
    }

    /**
 * Pin the prompt template version after ai-service selects the canary bucket for the first generation round.
     * Retry rounds read the pinned tp_ai_task.template_version_id instead of pulling the latest, so changing a template during execution does not affect in-flight tasks.
     */
    @PutMapping("/{taskId}/template-pin")
    public Response<Void> pinTemplate(@PathVariable long taskId,
                                      @RequestBody Map<String, Long> body) {
        aiTaskService.pinTemplate(taskId, body.get("templateVersionId"));
        return Response.builderSuccess(null);
    }

    /**
 * Latest single-line generation task of one project :
 * the AI generation page re-attaches a live task after a page leave/return
 * without a hand-carried task id. Read-only observation door.
 */
    @GetMapping("/latest-single")
    public Response<AiTaskInfo> latestSingle(@RequestParam long projectId) {
        return Response.builderSuccess(aiTaskService.findLatestSingle(projectId));
    }

    /**
 * Live generation markers for requirement points :
 * the requirement page annotates points whose case generation is still
 * queued/running. ids arrive comma-separated; an empty parameter honestly
 * answers an empty list. Read-only observation door.
 */
    @GetMapping("/live-by-requirements")
    public Response<java.util.List<com.sixbean.testpike.core.ai.api.dto.AiRequirementLiveInfo>>
            liveByRequirements(@RequestParam long projectId,
                               @RequestParam(name = "ids", required = false) String ids) {
        java.util.List<Long> parsed = ids == null || ids.isBlank()
                ? java.util.List.of()
                : java.util.Arrays.stream(ids.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .map(Long::parseLong)
                        .toList();
        return Response.builderSuccess(aiTaskService.liveByRequirements(projectId, parsed));
    }

 /** One-click retry resets only failed tasks to pending, increments retry_cnt, and re-enqueues and publishes the task to MQ. */
    @PostMapping("/{taskId}/retry")
    public Response<Void> retry(@PathVariable long taskId) {
        aiTaskService.retry(taskId);
        AiTaskInfo task = aiTaskService.get(taskId);
        if (task != null && task.status() == 1) {   // Republish only after successfully resetting the task to pending.
            // genSeq = the fresh retry_cnt: restarts re-publish the SAME taskId,
            // and without a per-generation suffix the consumer idempotency ledger
            // from the first run would swallow the new message as DUPLICATE
            // (the restarted queue row sat in the queued state forever, user
            // report 2026-11-20).
            aiGenPublisher.publish(new AiGenMessage(taskId, task.projectId(), task.operatorId(),
                    task.taskType(), task.requirementText(), task.requirementId(), task.caseId(), task.retryCnt()));
        }
        return Response.builderSuccess(null);
    }

    /**
     * Stop a live generation task (the process tail drawer's stop door).
     * Settles a queued/running task as cancelled; the writeback doors of the
     * stopped run then refuse every late result, so nothing generated after
     * the stop can be attached.
     */
    @PostMapping("/{taskId}/cancel")
    public Response<Void> cancel(@PathVariable long taskId) {
        aiTaskService.cancelTask(taskId);
        return Response.builderSuccess(null);
    }
}
