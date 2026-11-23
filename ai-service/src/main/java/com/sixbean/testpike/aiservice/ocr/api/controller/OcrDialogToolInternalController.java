package com.sixbean.testpike.aiservice.ocr.api.controller;

import com.sixbean.testpike.aiservice.ocr.api.dto.OcrDialogGridOutcome;
import com.sixbean.testpike.aiservice.ocr.api.dto.OcrDialogTextOutcome;
import com.sixbean.testpike.aiservice.ocr.api.dto.OcrDialogToolRequest;
import com.sixbean.testpike.aiservice.ocr.domain.service.OcrDialogToolService;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only dialog OCR doors (BF batch ; VisualLocateInternalController
 * family: internal doors need no 02 registration). Consumers are the two
 * registered conversation tools whose exec callback lands here through core
 * (exec_kind=1 HTTP); the controller stays thin — anchor validation,
 * ownership and clustering all live in the service single point.
 */
@RestController
@RequestMapping("/internal/ocr")
public class OcrDialogToolInternalController {

    private final OcrDialogToolService ocrDialogToolService;

    public OcrDialogToolInternalController(OcrDialogToolService ocrDialogToolService) {
        this.ocrDialogToolService = ocrDialogToolService;
    }

    /** Character post: engine lines (text + coordinates + confidence) + red-marked list. */
    @PostMapping("/text-extract")
    public Response<OcrDialogTextOutcome> textExtract(@RequestBody OcrDialogToolRequest request) {
        return Response.builderSuccess(ocrDialogToolService.textExtract(request));
    }

    /** Grid post: shared GridAssembler clustering, table/text/grid shape enum out. */
    @PostMapping("/grid-read")
    public Response<OcrDialogGridOutcome> gridRead(@RequestBody OcrDialogToolRequest request) {
        return Response.builderSuccess(ocrDialogToolService.gridRead(request));
    }
}
