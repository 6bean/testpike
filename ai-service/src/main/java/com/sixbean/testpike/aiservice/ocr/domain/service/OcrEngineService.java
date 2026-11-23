package com.sixbean.testpike.aiservice.ocr.domain.service;

import com.sixbean.testpike.aiservice.ocr.api.dto.OcrTextLine;

import java.util.List;

/**
 * Server-side CPU OCR engine .
 *
 * <p>Turns screenshot bytes into text lines with bounding boxes. Values
 * recognised here never reach any LLM prompt: the "values do not pass through
 * the model" rule of the screenshot login-state capture batch is enforced at
 * this boundary - callers get structured lines, not a model paraphrase.
 */
public interface OcrEngineService {

    /**
     * OCR one image (png/jpeg bytes). Fails closed with a named reason when
     * the worker process or the engine is unavailable - never a silent empty
     * result.
     */
    List<OcrTextLine> extract(byte[] imageBytes);
}
