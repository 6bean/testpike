package com.sixbean.testpike.aiservice.ocr.api.dto;

import java.util.List;

/**
 * Character-door output of the dialog OCR text tool (ocr.text_extract):
 * every engine line verbatim — text, confidence score and box coordinates —
 * plus the red-marked subset flattened to plain strings so the dialog can
 * echo flagged values without re-reading boxes. No table pretence: this door
 * answers "extract the text", the grid door answers "read the table".
 */
public record OcrDialogTextOutcome(List<OcrTextLine> lines,
                                   List<String> annotatedLines,
                                   int lineCount) {
}
