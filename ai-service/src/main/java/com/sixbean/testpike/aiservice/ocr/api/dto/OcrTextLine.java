package com.sixbean.testpike.aiservice.ocr.api.dto;

import java.util.List;

/**
 * One recognised text line from an OCR pass: the transcribed text, the engine
 * confidence (0-1), the 4-point bounding box in source-image pixel coordinates
 * and the red-annotation flag set by the worker's red pass .
 * Downstream grid alignment reads the box to map cells back to
 * table columns and the flag to mark candidate rows the user circled.
 */
public record OcrTextLine(String text, double score, List<List<Double>> box, boolean annotated) {
}
