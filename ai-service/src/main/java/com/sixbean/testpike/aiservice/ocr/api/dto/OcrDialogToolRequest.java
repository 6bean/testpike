package com.sixbean.testpike.aiservice.ocr.api.dto;

/**
 * One dialog OCR tool call .
 *
 * <p>{@code objectKey} names the chat image the model wants read and is the
 * only model-writable field. The remaining three are server-stamped
 * conversation anchors: the dispatcher seam overwrites whatever the model
 * wrote before the payload leaves ai-service (the PassThroughReadAnchor
 * posture), and both doors here refuse fail-closed when an anchor is missing
 * or not positive — an unreadable identity never rides on as permission.
 *
 * @param objectKey canonical chat-image key of one attached screenshot
 * @param chatSessionId conversation segment stamped by the server (owner check)
 * @param projectId project segment stamped by the server (read door anchor)
 * @param operatorId acting user stamped by the server (ledger door)
 */
public record OcrDialogToolRequest(String objectKey, Long chatSessionId,
                                   Long projectId, Long operatorId) {
}
