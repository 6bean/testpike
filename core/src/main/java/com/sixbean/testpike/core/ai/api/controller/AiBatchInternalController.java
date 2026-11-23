package com.sixbean.testpike.core.ai.api.controller;

import com.sixbean.testpike.core.ai.api.dto.AiBatchCreateInput;
import com.sixbean.testpike.core.ai.api.dto.AiBatchCreateRequest;
import com.sixbean.testpike.core.ai.api.dto.AiBatchCreateResult;
import com.sixbean.testpike.core.ai.api.dto.AiBatchEstimate;
import com.sixbean.testpike.core.ai.api.dto.AiBatchInfo;
import com.sixbean.testpike.common.aibatch.AiBatchItem;
import com.sixbean.testpike.common.aibatch.BatchItemNormalizer;
import com.sixbean.testpike.core.ai.domain.service.AiBatchService;
import com.sixbean.testpike.core.audit.domain.component.OperatorContext;
import com.sixbean.testpike.common.mq.AiGenMessage;
import com.sixbean.testpike.core.events.domain.service.AiGenPublisher;

import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Internal interface for AI batch generation (A2 batch generation moved earlier).
 * <p>Chain: admin POST /api/v1/ai/batch-generate → core creates a batch and creates tasks one by one (pending, associated by batch_id).
 * This layer loops to send MQ (ai.case.generate, consistent with the single-item persist-to-database + send-MQ layering), and ai-service consumes each task to generate.
 * Progress query GET /internal/ai-batches/{batchId} aggregates subtask statuses in real time (x/N).
 * Operator is passed through from X-Operator-Id to close the audit created_by loop.
 *
 */
@RestController
@RequestMapping("/internal/ai-batches")
public class AiBatchInternalController {

    private final AiBatchService aiBatchService;
    private final AiGenPublisher aiGenPublisher;

    public AiBatchInternalController(AiBatchService aiBatchService, AiGenPublisher aiGenPublisher) {
        this.aiBatchService = aiBatchService;
        this.aiGenPublisher = aiGenPublisher;
    }

 /** Cost estimation (pure calculation without persisting to database; data source for the pre-generation confirmation dialog, cost confirmation). */
    @PostMapping("/estimate")
    public Response<AiBatchEstimate> estimate(@RequestBody AiBatchCreateRequest request) {
        // the preview batch id rides through to the three-argument
        // overload so the dialog shows lines × scenarios × rounds, never a
        // fabricated multiplier (null = legacy per-line shape).
        return Response.builderSuccess(aiBatchService.estimate(request.projectId(),
                BatchItemNormalizer.texts(request.normalizedItems()), request.previewBatchId()));
    }

 /** Batch limit read (M7 /V8: dynamic data source for frontend count copy; K8 hot-update immediate value). */
    @GetMapping("/limit")
    public Response<Map<String, Integer>> limit() {
        // The auto-enumerate flag rides the same door so the page keeps one read
        // for both batch defaults (max rows + whether a preview-less submit
        // auto-expands scenarios). Values are 0/1 for the boolean.
        return Response.builderSuccess(Map.of(
                "maxCount", aiBatchService.currentMaxCount(),
                "autoEnumerate", aiBatchService.currentAutoEnumerate() ? 1 : 0));
    }

    /** Create batch generation (persist the batch and subtasks to database, send MQ one by one; return batchId, frontend polls progress). */
    @PostMapping
    public Response<Map<String, Long>> create(@RequestBody AiBatchCreateRequest request,
                                              @RequestHeader(value = "X-Operator-Id", required = false) Long operatorId) {
        long operator = operatorId != null ? operatorId
                : OperatorContext.currentOperatorId() != null ? OperatorContext.currentOperatorId() : 0L;
        List<AiBatchItem> items = request.normalizedItems();
        AiBatchCreateResult result = aiBatchService.createBatch(
                new AiBatchCreateInput(request.projectId(), operator, items, request.windowFeedDeclared()));
        var taskIds = result.taskIds();
        for (int i = 0; i < taskIds.size(); i++) {
            AiBatchItem item = items.get(i);
            // sourceAnchor stays contract-only on the DTO until the lineage batch stores it .
            aiGenPublisher.publish(new AiGenMessage(taskIds.get(i), request.projectId(), operator, 1,
                    item.text(), item.requirementId(), null), result.windowFeed());
        }
        return Response.builderSuccess(Map.of("batchId", result.batchId()));
    }

    /** Batch progress aggregation (GET x/N: done/failed/pending counts and item details; returns null if not found). */
    @GetMapping("/{batchId}")
    public Response<AiBatchInfo> get(@PathVariable long batchId) {
        return Response.builderSuccess(aiBatchService.get(batchId));
    }

    /**
 * Latest batch of one project, any state : the AI
 * generation page re-attaches the queue after a page leave/return without
 * a hand-carried batch id. Literal path wins over /{batchId} in Spring's
 * pattern matching; the id door never sees the word "latest".
 */
    @GetMapping("/latest")
    public Response<AiBatchInfo> latest(@RequestParam long projectId) {
        return Response.builderSuccess(aiBatchService.findLatestByProject(projectId));
    }
}
