package com.sixbean.testpike.admin.reportcenter.api.dto;

import java.math.BigDecimal;

/**
 * Per-project live pass-rate reading of the program overview feed
 * (mirror of the core record; NULL passRate = no executions in the window).
 */
public record ReportCenterProjectPassRateView(long projectId, BigDecimal passRate) {
}
