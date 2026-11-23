package com.sixbean.testpike.aiservice.devtoolspanel.domain.service;

import com.sixbean.testpike.aiservice.devtoolspanel.api.dto.DevToolsPanelParseResult;
import com.sixbean.testpike.aiservice.ocr.api.dto.OcrTextLine;

import java.util.List;

/**
 * Maps raw OCR text lines (with pixel boxes) of a DevTools Application panel
 * screenshot onto structured login-state candidate rows. Consumes the OCR
 * engine bridge as its only transcription source; performs no model call.
 */
public interface DevToolsPanelParseService {

    /** Parses one panel pass; a screenshot without any recognised header band yields an empty result. */
    DevToolsPanelParseResult parse(List<OcrTextLine> lines);
}
