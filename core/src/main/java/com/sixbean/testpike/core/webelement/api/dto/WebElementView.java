package com.sixbean.testpike.core.webelement.api.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Ledger row as stored. state: 0=candidate 1=confirmed 2=stale 3=retired.
 * confidence is the feedback-recomputed hit quality from execution reports,
 * never a model self-score (kept separate from evidence_grade by design,
 * WE batch W5). revision is the optimistic lock consumed by the anchor and
 * state doors (stale writes refuse with the shared 40901 semantics).
 */
public record WebElementView(Long id, Long projectId, String name, String pageKey,
                             List<WebElementAnchorInput> anchors,
                             List<WebElementVariantInput> variants, int state, double confidence,
                             long hitCount, long missCount, int revision,
                             LocalDateTime createdAt, LocalDateTime updatedAt) {
}
