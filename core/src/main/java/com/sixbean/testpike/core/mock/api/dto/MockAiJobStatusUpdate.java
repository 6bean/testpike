package com.sixbean.testpike.core.mock.api.dto;

/**
 * Status write-back body of one AI drafting job (explore status-update
 * precedent). The consumer sends the target status plus at most one payload
 * field: {@code resultPreviewRef} for running→preview, {@code errorMessage}
 * for running→failed. Which field is required is decided by the service's
 * transition spectrum — the controller stays thin and never re-validates
 * business rules.
 *
 * @param status           target status (running | preview | failed)
 * @param resultPreviewRef draft-bundle reference for the preview hand-off
 * @param errorMessage     plain-word failure reason for the failed seal
 */
public record MockAiJobStatusUpdate(String status, String resultPreviewRef, String errorMessage) {
}
