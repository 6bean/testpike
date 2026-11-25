package com.sixbean.testpike.core.mock.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.mock.api.dto.MockStubDraftAdvice;
import com.sixbean.testpike.core.mock.api.dto.SpecVerdictRefineInput;
import com.sixbean.testpike.core.mock.domain.service.MockStubImportService;
import com.sixbean.testpike.core.mock.domain.service.SpecImportVerdictService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Advice-joined preview door of the contract-bulk import wizard (MJ P3):
 * the plain draft list and its pre-adjudication arrive in one frame so the
 * wizard can render the default selection and the honest waiting label
 * without a second round-trip. The deterministic floor answers at once —
 * nothing here waits for an LLM, and nothing is written to the rule store:
 * importing still goes through the human confirm gate unchanged.
 */
@RestController
@RequestMapping("/internal/mock")
public class MockSpecImportAdviceController {

    private final MockStubImportService stubImportService;
    private final SpecImportVerdictService verdictService;

    public MockSpecImportAdviceController(MockStubImportService stubImportService,
                                          SpecImportVerdictService verdictService) {
        this.stubImportService = stubImportService;
        this.verdictService = verdictService;
    }

    /**
     * Drafts plus advice for one preview round. spaceId is optional: without
     * it the rows come back advice-free (no conflict reading is possible when
     * the target space is unknown), keeping the older plain-preview callers
     * working while the space workbench wizard passes its own space id.
     */
    @GetMapping("/stub/drafts-advised")
    public Response<List<MockStubDraftAdvice>> stubDraftsAdvised(
            @RequestParam long specId,
            @RequestParam(required = false) Long spaceId) {
        if (spaceId == null) {
            return Response.builderSuccess(
                    MockStubDraftAdvice.withoutAdvice(stubImportService.previewDrafts(specId)));
        }
        return Response.builderSuccess(verdictService.advise(
                specId, spaceId, stubImportService.previewDrafts(specId)));
    }

    /**
     * AI backfill intake of the bulk-enrichment job family (single writer):
     * refined advice rows update the stored deterministic floor in place, so
     * the wizard shows refined rows inside the deadline and honestly degrades
     * to rule advice past it. Writes no rule; the human confirm gate is
     * untouched by this door.
     */
    @PostMapping("/spec-import/verdict-refine")
    public Response<Boolean> refineVerdicts(@RequestBody SpecVerdictRefineInput input) {
        verdictService.applyAiRefinement(input.specId(), input.spaceId(), input.toVerdictRows());
        return Response.builderSuccess(Boolean.TRUE);
    }
}
