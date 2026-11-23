package com.sixbean.testpike.admin.deadletter.api.controller;

import com.sixbean.testpike.admin.deadletter.api.dto.DeadLetterClassCountView;
import com.sixbean.testpike.admin.deadletter.api.dto.DeadLetterLedgerRowView;
import com.sixbean.testpike.admin.deadletter.api.dto.DeadLetterQueueCountView;
import com.sixbean.testpike.admin.deadletter.api.dto.DeadLetterReplayReceiptView;
import com.sixbean.testpike.admin.deadletter.infrastructure.remote.client.RestDeadLetterCoreClient;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Dead-letter ops triage doors .
 * <p>The page consumes exactly three shapes — per-queue grouping, failure-class
 * distribution and paged sampling with the plain-language reason. admin only
 * authenticates and forwards; backlog basis is always ledger rows in a status
 * (pending by default), never the Rabbit queue depth, and replay/void write doors
 * land with behind their own keys.
 */
@RestController
@RequestMapping("/api/v1/mq-dead-letter")
public class DeadLetterOpsController {

    private final RestDeadLetterCoreClient coreClient;

    public DeadLetterOpsController(RestDeadLetterCoreClient coreClient) {
        this.coreClient = coreClient;
    }

    /** Per-queue backlog grouping (status omitted = pending ledger rows). */
    @GetMapping("/queue-backlog")
    @RequirePermission("perm:system:dead-letter-read")
    public Response<List<DeadLetterQueueCountView>> queueBacklog(
            @RequestParam(required = false) String status) {
        return Response.builderSuccess(coreClient.queueBacklog(status));
    }

    /** Failure-class histogram for triage (optional queue scope). */
    @GetMapping("/class-distribution")
    @RequirePermission("perm:system:dead-letter-read")
    public Response<List<DeadLetterClassCountView>> classDistribution(
            @RequestParam(required = false) String queueName,
            @RequestParam(required = false) String status) {
        return Response.builderSuccess(coreClient.classDistribution(queueName, status));
    }

    /** Paged ledger sampling, newest first (payload + plain reason, no raw stack). */
    @GetMapping("/browse")
    @RequirePermission("perm:system:dead-letter-read")
    public Response<PageView<DeadLetterLedgerRowView>> browse(
            @RequestParam(required = false) String queueName,
            @RequestParam(required = false) String failureClass,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        var result = coreClient.browse(queueName, failureClass, status, page, size);
        return Response.builderSuccess(new PageView<>(result.list(), result.total()));
    }

    /** Replay one pending row (guards and rejection messages are core single-source). */
    @PostMapping("/replay/one")
    @RequirePermission("perm:system:dead-letter-replay")
    public Response<DeadLetterReplayReceiptView> replayOne(
            @RequestParam long ledgerId,
            @RequestParam(defaultValue = "false") boolean confirmReexecute) {
        return Response.builderSuccess(coreClient.replayOne(ledgerId, confirmReexecute));
    }

    /** Replay the pending rows of one failure class inside one DLQ. */
    @PostMapping("/replay/by-class")
    @RequirePermission("perm:system:dead-letter-replay")
    public Response<Integer> replayByClass(
            @RequestParam String queueName,
            @RequestParam String failureClass,
            @RequestParam(defaultValue = "false") boolean confirmReexecute) {
        return Response.builderSuccess(coreClient.replayByClass(queueName, failureClass, confirmReexecute));
    }

    /** Replay the whole queue (ENTITY_GONE rows stay pending for voiding). */
    @PostMapping("/replay/queue")
    @RequirePermission("perm:system:dead-letter-replay")
    public Response<Integer> replayQueue(
            @RequestParam String queueName,
            @RequestParam(defaultValue = "false") boolean confirmReexecute) {
        return Response.builderSuccess(coreClient.replayQueue(queueName, confirmReexecute));
    }

    /** Void one pending row with a recorded reason; nothing is ever deleted. */
    @PostMapping("/void")
    @RequirePermission("perm:system:dead-letter-void")
    public Response<Boolean> voidLedger(
            @RequestParam long ledgerId,
            @RequestParam String reason) {
        return Response.builderSuccess(coreClient.voidLedger(ledgerId, reason));
    }
}
