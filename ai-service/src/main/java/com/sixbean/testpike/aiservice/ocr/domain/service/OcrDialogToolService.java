package com.sixbean.testpike.aiservice.ocr.domain.service;

import com.sixbean.testpike.aiservice.ocr.api.dto.OcrDialogGridOutcome;
import com.sixbean.testpike.aiservice.ocr.api.dto.OcrDialogTextOutcome;
import com.sixbean.testpike.aiservice.ocr.api.dto.OcrDialogToolRequest;

/**
 * Read-only dialog OCR doors : two registered conversation
 * tools share one image-handle input shape and one ownership seam.
 *
 * <ul>
 * <li>{@link #textExtract} — character post: engine lines verbatim (text +
 * coordinates + confidence) plus the red-marked list, tables not required;</li>
 * <li>{@link #gridRead} — grid post: OCR first, then the shared
 * {@link GridAssembler} clustering, answering with the table/text/grid
 * shape enum (never a second clustering implementation, R40).</li>
 * </ul>
 *
 * <p>Both doors refuse fail-closed before touching object storage when the
 * server-stamped conversation anchor is missing, the image handle was not
 * booked into the caller's own session, or the session ledger is unreachable.
 */
public interface OcrDialogToolService {

    OcrDialogTextOutcome textExtract(OcrDialogToolRequest request);

    OcrDialogGridOutcome gridRead(OcrDialogToolRequest request);
}
