package com.sixbean.testpike.admin.ai.api.controller;

import com.sixbean.testpike.admin.ai.api.dto.AiBatchEstimateView;
import com.sixbean.testpike.admin.ai.api.dto.AiBatchGenerateRequest;
import com.sixbean.testpike.admin.ai.api.dto.AiBatchView;
import com.sixbean.testpike.admin.ai.api.dto.AiGenerateRequest;
import com.sixbean.testpike.admin.ai.api.dto.AiRequirementLiveView;
import com.sixbean.testpike.admin.ai.api.dto.AiTaskEventView;
import com.sixbean.testpike.admin.ai.api.dto.AiTaskView;
import com.sixbean.testpike.admin.ai.api.dto.ScenarioPreviewRequest;
import com.sixbean.testpike.admin.ai.api.dto.ScenarioPreviewView;
import com.sixbean.testpike.admin.ai.app.service.StepsGenEventStreamBridge;
import com.sixbean.testpike.admin.ai.infrastructure.remote.client.CoreAiBatchClient;
import com.sixbean.testpike.admin.ai.infrastructure.remote.client.CoreAiScenarioPreviewClient;
import com.sixbean.testpike.admin.ai.infrastructure.remote.client.CoreAiTaskClient;
import com.sixbean.testpike.admin.aichat.app.component.SseFrameWriter;
import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.common.idempotent.IdempotentGuard;
import com.sixbean.testpike.common.clientobject.Response;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


@RestController
@RequestMapping("/api/v1/ai")
public class AiController {

    private final CoreAiTaskClient coreAiTaskClient;
    private final CoreAiBatchClient coreAiBatchClient;
    private final CoreAiScenarioPreviewClient coreAiScenarioPreviewClient;
    private final ProjectAccessGuard guard;
    private final IdempotentGuard idempotentGuard;
    private final StepsGenEventStreamBridge stepsGenStreamBridge;

    /** Hard ceiling for one live tail drawer — the pump's own guards seal far earlier. */
    private static final long SSE_TIMEOUT_MILLIS = 30 * 60 * 1000L;

    /** Page ceiling of the requirement live-generation annotation : one table page at a time. */
    private static final int LIVE_REQUIREMENT_IDS_MAX = 200;

    public AiController(CoreAiTaskClient coreAiTaskClient, CoreAiBatchClient coreAiBatchClient,
                        CoreAiScenarioPreviewClient coreAiScenarioPreviewClient,
                        ProjectAccessGuard guard, IdempotentGuard idempotentGuard,
                        StepsGenEventStreamBridge stepsGenStreamBridge) {
        this.coreAiTaskClient = coreAiTaskClient;
        this.coreAiBatchClient = coreAiBatchClient;
        this.coreAiScenarioPreviewClient = coreAiScenarioPreviewClient;
        this.guard = guard;
        this.idempotentGuard = idempotentGuard;
        this.stepsGenStreamBridge = stepsGenStreamBridge;
    }

    
    @PostMapping("/generate")
    public Response<Map<String, Long>> generate(@RequestBody AiGenerateRequest request,
                                                @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        guard.assertCanWriteCase(UserContext.current(), request.projectId());
        long operatorId = UserContext.current().userId();
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            String key = "ai:generate:" + request.projectId() + ":" + idempotencyKey;
            return Response.builderSuccess(idempotentGuard.execute(key, () ->
                    coreAiTaskClient.createTask(request.projectId(), operatorId, 1,
                            request.requirement(), request.requirementId())));
        }
        return Response.builderSuccess(coreAiTaskClient.createTask(request.projectId(), operatorId, 1,
                request.requirement(), request.requirementId()));
    }

    
    @GetMapping("/tasks/{taskId}")
    public Response<AiTaskView> getTask(@PathVariable long taskId) {
        AiTaskView task = coreAiTaskClient.getTask(taskId);
        if (task != null) {
            guard.assertCanView(UserContext.current(), task.projectId());
        }
        return Response.builderSuccess(task);
    }

    /**
 * Cursor window of one step-generation task's process events ( S4
 * segment B sub-2): the tail drawer's poll fallback, read-only. Ownership
 * gate = the existing project-member check resolved from the task's own
 * project (same posture as the extraction tail door, no second permission
 * model); an absent limit defers to the core hot key ai.stream.chunk-size.
 */
    @GetMapping("/tasks/{taskId}/process-events")
    public Response<List<AiTaskEventView>> taskProcessEvents(
            @PathVariable long taskId,
            @RequestParam(defaultValue = "0") long cursor,
            @RequestParam(required = false) Integer limit) {
        CurrentUser user = UserContext.current();
        AiTaskView task = coreAiTaskClient.getTask(taskId);
        if (task != null) {
            guard.assertCanView(user, task.projectId());
        }
        return Response.builderSuccess(
                coreAiTaskClient.taskProcessEvents(taskId, cursor, limit));
    }

    /**
     * Live tail of one step-generation task's process events (same two-door
     * posture as the extraction stream): one SSE stream per open drawer,
     * frames ride in strictly increasing cursor order and the stream seals
     * when the task settles. The pump loop is the shared single source —
     * this door only checks ownership, sets the anti-buffering headers and
     * hands the cursor to the bridge on a virtual thread. A refused member
     * never starts a tail.
     */
    @GetMapping("/tasks/{taskId}/process-events/stream")
    public SseEmitter taskProcessEventStream(@PathVariable long taskId,
                                             @RequestParam(defaultValue = "0") long cursor,
                                             HttpServletResponse response) {
        CurrentUser user = UserContext.current();
        AiTaskView task = coreAiTaskClient.getTask(taskId);
        if (task != null) {
            guard.assertCanView(user, task.projectId());
        }
        // Proxy buffering would batch the frames and recreate the dead-spinner
        // failure the tail exists to prevent (same header pair as the dialog door).
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache");
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
        SseFrameWriter writer = new SseFrameWriter(emitter);
        Thread.ofVirtual().name("steps-gen-tail-" + taskId).start(() -> {
            try {
                stepsGenStreamBridge.stream(taskId, cursor, writer);
            } catch (RuntimeException pumpDied) {
                writer.write("error", cursor + 1,
                        "生成过程通道暂时不可用：" + pumpDied.getMessage(), null);
                writer.complete();
            }
        });
        return emitter;
    }

 
    @PostMapping("/tasks/{taskId}/retry")
    public Response<Void> retry(@PathVariable long taskId) {
        AiTaskView task = coreAiTaskClient.getTask(taskId);
        if (task != null) {
            guard.assertCanWriteCase(UserContext.current(), task.projectId());
        }
        coreAiTaskClient.retryTask(taskId);
        return Response.builderSuccess(null);
    }

    /**
     * Stop a queued/running generation task (the tail drawer's stop door).
     * The write gate follows the retry law — membership plus the read-only
     * developer refusal, resolved from the task's own project — and the stop
     * semantic itself lives single-source in core (terminal rows are refused
     * there, late writebacks of a stopped run are dropped there).
     */
    @PostMapping("/tasks/{taskId}/cancel")
    public Response<Void> cancel(@PathVariable long taskId) {
        AiTaskView task = coreAiTaskClient.getTask(taskId);
        if (task != null) {
            guard.assertCanWriteCase(UserContext.current(), task.projectId());
        }
        coreAiTaskClient.cancelTask(taskId);
        return Response.builderSuccess(null);
    }

 
    @PostMapping("/batch/estimate")
    public Response<AiBatchEstimateView> batchEstimate(@RequestBody AiBatchGenerateRequest request) {
        guard.assertCanWriteCase(UserContext.current(), request.projectId());
        return Response.builderSuccess(coreAiBatchClient.estimateBatch(
                request.projectId(), request.normalizedItems(), request.previewBatchId()));
    }

 
    @PostMapping("/batch/scenario-preview")
    public Response<Long> runScenarioPreview(@RequestBody ScenarioPreviewRequest request) {
        guard.assertCanWriteCase(UserContext.current(), request.projectId());
        long operatorId = UserContext.current().userId();
        return Response.builderSuccess(coreAiScenarioPreviewClient.create(request, operatorId));
    }

 
    @GetMapping("/batch/scenario-previews")
    public Response<List<ScenarioPreviewView>> listScenarioPreviews(@RequestParam long batchId) {
        long operatorId = UserContext.current().userId();
        List<ScenarioPreviewView> rows = coreAiScenarioPreviewClient.listByBatch(batchId, operatorId);
        if (rows != null && !rows.isEmpty()) {
            guard.assertCanView(UserContext.current(), rows.get(0).projectId());
        }
        return Response.builderSuccess(rows == null ? List.of() : rows);
    }

 
    @GetMapping("/batch/limit")
    public Response<Map<String, Integer>> batchLimit() {
        // Both batch defaults ride one door: {maxCount, autoEnumerate(0/1)}.
        return Response.builderSuccess(coreAiBatchClient.batchLimit());
    }

 
 @PostMapping("/batch-generate")
 public Response<Map<String, Long>> batchGenerate(@RequestBody AiBatchGenerateRequest request,
                                                  @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                                  @RequestHeader(value = "X-Window-Feed", required = false) String windowFeed) {
     guard.assertCanWriteCase(UserContext.current(), request.projectId());
     long operatorId = UserContext.current().userId();
     // Optional verification-window feed declaration (X-1/true), same optional
     // header posture as X-Operator-Id; core keeps the authority to honor it.
     boolean declared = windowFeed != null
             && ("1".equals(windowFeed.trim()) || "true".equalsIgnoreCase(windowFeed.trim()));
     if (idempotencyKey != null && !idempotencyKey.isBlank()) {
         String key = "ai:batch:" + request.projectId() + ":" + idempotencyKey;
         return Response.builderSuccess(idempotentGuard.execute(key, () ->
                 coreAiBatchClient.createBatch(request.projectId(), request.normalizedItems(),
                         operatorId, declared)));
     }
     return Response.builderSuccess(
             coreAiBatchClient.createBatch(request.projectId(), request.normalizedItems(),
                     operatorId, declared));
 }

    
    @GetMapping("/batches/{batchId}")
    public Response<AiBatchView> getBatch(@PathVariable long batchId) {
        AiBatchView batch = coreAiBatchClient.getBatch(batchId);
        if (batch != null) {
            guard.assertCanView(UserContext.current(), batch.projectId());
        }
        return Response.builderSuccess(batch);
    }

    /**
 * Recovery door of the AI generation page : the latest batch
 * and the latest single-line task of the project segment, any state, null
 * when never run. The page re-attaches the queue and the progress panel
 * from this single read after the user leaves and comes back — no local
 * second book (the ledger itself stays the source). Read gate = project
 * view of the requested segment (both rows are project-filtered core-side).
 */
    @GetMapping("/generation/latest")
    public Response<Map<String, Object>> latestGeneration(@RequestParam long projectId) {
        guard.assertCanView(UserContext.current(), projectId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("batch", coreAiBatchClient.latestBatch(projectId));
        payload.put("task", coreAiTaskClient.latestSingleTask(projectId));
        return Response.builderSuccess(payload);
    }

    /**
 * Live generation markers for requirement points : powers the
 * "case generating" badge on the requirement management page. The id list
 * is page-bounded (the caller reads one table page at a time); a larger
 * list is honestly refused with the current count and the cap named.
 */
    @GetMapping("/generation/live-requirements")
    public Response<List<AiRequirementLiveView>> liveRequirementGenerations(
            @RequestParam long projectId,
            @RequestParam(name = "ids", required = false) List<Long> ids) {
        guard.assertCanView(UserContext.current(), projectId);
        if (ids == null || ids.isEmpty()) {
            return Response.builderSuccess(List.of());
        }
        if (ids.size() > LIVE_REQUIREMENT_IDS_MAX) {
            throw new com.sixbean.testpike.common.exception.ServiceException(
                    "需求点注解一次最多 " + LIVE_REQUIREMENT_IDS_MAX + " 个（当前传入 " + ids.size()
                            + " 个）：请按列表分页查看，本页需求点会自动标注生成状态",
                    com.sixbean.testpike.common.exception.BizErrorCode.VALIDATION_ERROR);
        }
        return Response.builderSuccess(coreAiTaskClient.liveByRequirements(projectId, ids));
    }
}
