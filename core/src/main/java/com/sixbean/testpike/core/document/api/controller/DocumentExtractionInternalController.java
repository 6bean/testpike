package com.sixbean.testpike.core.document.api.controller;

import com.sixbean.testpike.core.audit.domain.component.OperatorContext;
import com.sixbean.testpike.core.document.api.dto.ConfirmExtractionResult;
import com.sixbean.testpike.core.document.api.dto.DocumentInfo;
import com.sixbean.testpike.core.document.api.dto.ExtractRequest;
import com.sixbean.testpike.core.document.api.dto.ExtractionFailedRequest;
import com.sixbean.testpike.core.document.api.dto.ExtractionItem;
import com.sixbean.testpike.core.document.api.dto.ExtractionEventRow;
import com.sixbean.testpike.core.document.api.dto.ExtractionPreview;
import com.sixbean.testpike.core.document.api.dto.ExtractionResultWriteRequest;
import com.sixbean.testpike.core.document.domain.service.DocumentExtractionService;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;


@RestController
@RequestMapping("/internal/documents")
public class DocumentExtractionInternalController {

    private final DocumentExtractionService documentExtractionService;

    public DocumentExtractionInternalController(DocumentExtractionService documentExtractionService) {
        this.documentExtractionService = documentExtractionService;
    }

    /** Submit extraction (body {modelHint?, preempt?}; 409 human message while
     *  running; retry idempotency = confirmed items never duplicate). */
    @PostMapping("/{id}/extract")
    public Response<DocumentInfo> extract(@PathVariable long id,
                                          @RequestBody(required = false) ExtractRequest request) {
        String modelHint = request == null ? null : request.modelHint();
        String dimensionHint = request == null ? null : request.dimensionHint();
        boolean preempt = request != null && request.preempt();
        return Response.builderSuccess(documentExtractionService.extract(
                OperatorContext.currentOperatorId(), id, modelHint, dimensionHint, preempt,
                request == null ? null : request.chunkIds()));
    }

    /**
 * ④: current extraction-generation token (ai-service generation
 * self-check read endpoint). The token is internal generation-reconciliation
 * data, never a user-facing display value; same token = keep running,
 * foreign token = this superseded run aborts itself.
 */
    @GetMapping("/{id}/extract-token")
    public Response<String> extractToken(@PathVariable long id) {
        return Response.builderSuccess(documentExtractionService.currentExtractToken(id));
    }

    
    @GetMapping("/{id}/extraction")
    public Response<ExtractionPreview> extraction(@PathVariable long id,
                                                  @RequestParam(defaultValue = "1") int page,
                                                  @RequestParam(defaultValue = "20") int size) {
        return Response.builderSuccess(documentExtractionService.extraction(id, page, size));
    }

    /** cursor window of the live run's process events
 * (cursor = last row id the viewer saw; an absent limit falls back to the
 * hot key ai.stream.chunk-size, clamped 10~1000). Read-only observation
 * face — the project-ownership gate sits on the admin controller. */
    @GetMapping("/{id}/extraction-events")
    public Response<List<ExtractionEventRow>> extractionEvents(
            @PathVariable long id,
            @RequestParam(defaultValue = "0") long cursor,
            @RequestParam(required = false) Integer limit) {
        return Response.builderSuccess(
                documentExtractionService.extractionEvents(id, cursor, limit));
    }

    /**
 * segment C1 (R62): cursor window of the newest channel-B spec
 * extraction attempt for this document (cursor = last row id the viewer
 * saw; absent limit falls back to the hot key ai.stream.chunk-size).
 * Read-only observation face — the project-ownership gate sits on the
 * admin controller, same posture as the extraction-events door above.
 */
    @GetMapping("/{id}/spec-extraction-events")
    public Response<List<ExtractionEventRow>> specExtractionEvents(
            @PathVariable long id,
            @RequestParam(defaultValue = "0") long cursor,
            @RequestParam(required = false) Integer limit) {
        return Response.builderSuccess(
                documentExtractionService.specExtractionEvents(id, cursor, limit));
    }

    /** Tri-state confirm (body {items[], dispositions?}; dispositions = preview item id -> KEEP_AS_IS/FORCE_NEW). */
    @PostMapping("/{id}/confirm-extraction")
    public Response<ConfirmExtractionResult> confirmExtraction(
            @PathVariable long id, @RequestBody ConfirmExtractionBody request) {
        return Response.builderSuccess(documentExtractionService.confirmExtraction(
                OperatorContext.currentOperatorId(), id, request.items(), request.dispositions()));
    }

    /**
     * Full-store confirm (no body): the server lands every item of the
     * current extraction preview in one transaction, so pagination in the
     * viewer can never cap the result. Same gates as the checkbox door.
     */
    @PostMapping("/{id}/confirm-all-extraction")
    public Response<ConfirmExtractionResult> confirmAllExtraction(@PathVariable long id) {
        return Response.builderSuccess(documentExtractionService.confirmAllExtraction(
                OperatorContext.currentOperatorId(), id));
    }

    /** Confirm-door request body (the writeback record stays untouched on purpose). */
    public record ConfirmExtractionBody(List<ExtractionItem> items,
                                        Map<Long, String> dispositions) {
    }

    /**
 * ③: explicit cancel of a running extraction (rotates the
 * generation, settles the row to 1 with the human cancel reason; honest
 * rejection when nothing is extracting).
 */
    @PostMapping("/{id}/extraction-cancel")
    public Response<DocumentInfo> cancelExtraction(@PathVariable long id) {
        return Response.builderSuccess(documentExtractionService.cancelExtraction(
                OperatorContext.currentOperatorId(), id));
    }

    
    @PostMapping("/{id}/extraction-result")
    public Response<Void> writeExtractionResult(@PathVariable long id,
                                                @RequestBody ExtractionResultWriteRequest request) {
        documentExtractionService.writeExtractionResult(id, request.items(), request.extractToken());
        return Response.builderSuccess(null);
    }

    
    @PostMapping("/{id}/extraction-failed")
    public Response<Void> markExtractionFailed(@PathVariable long id,
                                               @RequestBody ExtractionFailedRequest request) {
        documentExtractionService.markExtractionFailed(id, request.errorMsg(), request.extractToken());
        return Response.builderSuccess(null);
    }
}
