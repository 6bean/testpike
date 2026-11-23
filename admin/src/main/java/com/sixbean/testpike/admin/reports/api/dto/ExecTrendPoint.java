package com.sixbean.testpike.admin.reports.api.dto;


public record ExecTrendPoint(String date, long passCount, long failCount,
                             long timeoutCount, double passRate) {
}
