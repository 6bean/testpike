package com.sixbean.testpike.admin.webelement.api.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Ledger row as stored. state: 0=candidate 1=confirmed 2=stale 3=retired.
 * confidence is the feedback-recomputed hit quality from execution reports,
 * never a model self-score (kept separate from evidence_grade by design,
 * WE batch W5). revision is the optimistic lock the anchor and state doors
 * consume on write.
 */
public record WebElementLedgerView(Long id, Long projectId, String name, String pageKey,
                                   List<WebElementAnchor> anchors, int state, double confidence,
                                   long hitCount, long missCount, int revision,
                                   LocalDateTime createdAt, LocalDateTime updatedAt) {
}
