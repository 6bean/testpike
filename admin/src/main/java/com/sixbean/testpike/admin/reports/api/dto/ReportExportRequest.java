package com.sixbean.testpike.admin.reports.api.dto;

/**
 *
 * @param reportType personal_review/team_board/ai_quality/coverage/execution_stat
 */
public record ReportExportRequest(long projectId, String reportType, boolean masked) {
}
