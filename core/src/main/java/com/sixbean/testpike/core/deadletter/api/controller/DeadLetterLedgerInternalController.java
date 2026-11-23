package com.sixbean.testpike.core.deadletter.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.exception.ValidationException;
import com.sixbean.testpike.core.audit.domain.component.OperatorContext;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.deadletter.api.dto.DeadLetterBrowseQuery;
import com.sixbean.testpike.core.deadletter.api.dto.DeadLetterClassCount;
import com.sixbean.testpike.core.deadletter.api.dto.DeadLetterLedgerRow;
import com.sixbean.testpike.core.deadletter.api.dto.DeadLetterQueueCount;
import com.sixbean.testpike.core.deadletter.api.dto.DeadLetterReplayReceipt;
import com.sixbean.testpike.core.deadletter.domain.component.DeadLetterFailureClass;
import com.sixbean.testpike.core.deadletter.domain.service.DeadLetterLedgerQueryService;
import com.sixbean.testpike.core.deadletter.domain.service.DeadLetterReplayService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

/**
 * Dead-letter ledger door .
 * <p>core only serves /internal/** — the admin ops page consumes these atomic queries
 * and the four action doors through the composition layer (service-layer rule). The
 * controller stays thin: parameters are forwarded untouched so status defaults,
 * whitelist checks, the ENTITY_GONE replay ban and the fail-closed messages live in
 * exactly one place (the query/replay services), never duplicated here. The acting
 * operator comes from the X-Operator-Id header (OperatorContext audit chain, red line:
 * never taken off the request object itself).
 */
@RestController
@RequestMapping("/internal/mq-dead-letter")
public class DeadLetterLedgerInternalController {

    private final DeadLetterLedgerQueryService queryService;
    private final DeadLetterReplayService replayService;

    public DeadLetterLedgerInternalController(DeadLetterLedgerQueryService queryService,
                                              DeadLetterReplayService replayService) {
        this.queryService = queryService;
        this.replayService = replayService;
    }

    /** Ledger rows per DLQ for one status (null = pending backlog basis). */
    @GetMapping("/queue-backlog")
    public Response<List<DeadLetterQueueCount>> queueBacklog(
            @RequestParam(required = false) String status) {
        return Response.builderSuccess(queryService.queueBacklog(status));
    }

    /** Failure-class histogram within an optional queue scope. */
    @GetMapping("/class-distribution")
    public Response<List<DeadLetterClassCount>> classDistribution(
            @RequestParam(required = false) String queueName,
            @RequestParam(required = false) String status) {
        return Response.builderSuccess(queryService.classDistribution(queueName, status));
    }

    /** Paged newest-first browsing with payload and plain-language reason. */
    @GetMapping("/browse")
    public Response<PageResult<DeadLetterLedgerRow>> browse(
            @RequestParam(required = false) String queueName,
            @RequestParam(required = false) String failureClass,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        DeadLetterBrowseQuery query = new DeadLetterBrowseQuery(
                queueName, failureClass, status,
                page == null ? 1 : page,
                size == null ? 20 : size);
        return Response.builderSuccess(queryService.browse(query));
    }

    /** Replay one pending ledger row back to its original exchange/routing key. */
    @PostMapping("/replay/one")
    public Response<DeadLetterReplayReceipt> replayOne(
            @RequestParam long ledgerId,
            @RequestParam(defaultValue = "false") boolean confirmReexecute) {
        return Response.builderSuccess(replayService.replayOne(ledgerId, confirmReexecute, currentOperator()));
    }

    /** Replay all pending rows of one DLQ within a failure class (ENTITY_GONE is hard-rejected). */
    @PostMapping("/replay/by-class")
    public Response<Integer> replayByClass(
            @RequestParam String queueName,
            @RequestParam String failureClass,
            @RequestParam(defaultValue = "false") boolean confirmReexecute) {
        return Response.builderSuccess(replayService.replayByFailureClass(
                queueName, parseFailureClass(failureClass), confirmReexecute, currentOperator()));
    }

    /** Replay every replayable pending row of one DLQ (ENTITY_GONE rows stay pending). */
    @PostMapping("/replay/queue")
    public Response<Integer> replayQueue(
            @RequestParam String queueName,
            @RequestParam(defaultValue = "false") boolean confirmReexecute) {
        return Response.builderSuccess(replayService.replayQueue(queueName, confirmReexecute, currentOperator()));
    }

    /** Void one pending row with a recorded reason; the row is never deleted. */
    @PostMapping("/void")
    public Response<Boolean> voidLedger(
            @RequestParam long ledgerId,
            @RequestParam String reason) {
        return Response.builderSuccess(replayService.voidLedger(ledgerId, reason, currentOperator()));
    }

    /** Acting operator from the X-Operator-Id audit chain (null → the service rejects in person-language). */
    private static String currentOperator() {
        Long operatorId = OperatorContext.currentOperatorId();
        return operatorId == null ? null : String.valueOf(operatorId);
    }

    /**
     * Vocabulary conversion only: the literal is matched against the single-source
     * enum names; an unknown literal is rejected in person-language listing the valid
     * choices. The replay bans (ENTITY_GONE etc.) stay inside the replay service.
     */
    private static DeadLetterFailureClass parseFailureClass(String literal) {
        String value = literal == null ? "" : literal.trim();
        for (DeadLetterFailureClass candidate : DeadLetterFailureClass.values()) {
            if (candidate.name().equals(value)) {
                return candidate;
            }
        }
        throw new ValidationException("失败分型无效（" + value + "），可选值："
                + String.join("/", Arrays.stream(DeadLetterFailureClass.values())
                        .map(DeadLetterFailureClass::name).toList()));
    }
}
