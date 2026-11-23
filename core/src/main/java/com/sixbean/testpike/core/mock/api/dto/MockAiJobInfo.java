package com.sixbean.testpike.core.mock.api.dto;

import java.time.LocalDateTime;

/**
 * AI drafting job view (control-plane read of one tp_mock_ai_job row).
 * budgetSnapshotJson is the creation-time pin verbatim — the UI and the
 * ai-service consumer both read this text instead of recomputing from live
 * global keys (explore V57 precedent).
 *
 * @param id                job row id
 * @param spaceId           owning mock space
 * @param jobType           application point (nl-stub | edge-scenarios | spec-sync | ...)
 * @param status            queued | running | preview | consumed | failed | cancelled
 * @param inputJson         drafting input as stored (prompt + references)
 * @param budgetSnapshotJson creation-time cost account pin (JSON text, may be null
 *                          for pre-V239 rows)
 * @param resultPreviewRef  artifact reference of the human-ticked preview bundle
 * @param errorMessage      plain-word failure reason when status=failed
 * @param createdAt         creation stamp
 * @param updatedAt         last transition stamp
 */
public record MockAiJobInfo(
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
