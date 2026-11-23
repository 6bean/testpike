package com.sixbean.testpike.admin.mock.api.dto;

import java.time.LocalDateTime;

/**
 * AI drafting job view (admin bridge; field-for-field mirror of the core
 * MockAiJobInfo record). budgetSnapshotJson passes through verbatim — the UI
 * shows the creation-time cost pin, never a recomputed live estimate.
 */
public record MockAiJobView(
        Long id,
        Long spaceId,
        String jobType,
        String status,
        String inputJson,
        String budgetSnapshotJson,
        String resultPreviewRef,
        String errorMessage,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
