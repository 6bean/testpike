package com.sixbean.testpike.core.reportcenter.api.dto;

import java.time.LocalDateTime;

/**
 *
 * @param scopeType 1=project 2=global
 * @param reportType 1=weekly 2=monthly
 */
public record ReportListItem(Long id, Integer scopeType, Long scopeId, Integer reportType,
                             LocalDateTime periodStart, LocalDateTime periodEnd,
                             LocalDateTime generatedAt, Long createdBy) {
}
