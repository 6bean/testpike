package com.sixbean.testpike.core.webelement.api.dto;

import java.util.List;

/**
 * Paged read result for the self-heal evidence ledger. projectId is filled
 * by the task-scope door only (the owning execution task is JOINed there)
 * so the admin side can run its membership guard without a second lookup;
 * the element-scope door leaves it null because the caller already passed
 * the element's own guard.
 */
public record HealEvidencePage(
        List<StepHealEvidenceView> items,
        long total,
        int page,
        int pageSize,
        Long projectId) {
}
