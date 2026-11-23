package com.sixbean.testpike.admin.webelement.api.dto;

import java.util.List;

/**
 * Admin mirror of the core paged self-heal evidence result. projectId is
 * filled by the task-scope door only (used for the membership guard); the
 * element-scope door leaves it null.
 */
public record HealEvidencePage(
        List<StepHealEvidenceView> items,
        long total,
        int page,
        int pageSize,
        Long projectId) {
}
