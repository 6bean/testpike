package com.sixbean.testpike.aiservice.ocr.api.dto;

import java.util.List;

/**
 * Grid-door output of the dialog OCR tool . {@code form} is
 * the shape verdict; {@code markdown} is filled only for {@link
 * OcrDialogGridForm#TABLE} (GFM, pipes escaped, never fabricated); {@code
 * rows} is the assembled row matrix for every form so a consumer can fall
 * back to raw cells; {@code lineCount} counts the engine lines the assembly
 * consumed.
 */
public record OcrDialogGridOutcome(OcrDialogGridForm form,
                                   String markdown,
                                   List<List<String>> rows,
                                   int lineCount) {
}
