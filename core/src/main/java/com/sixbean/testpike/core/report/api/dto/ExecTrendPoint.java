package com.sixbean.testpike.core.report.api.dto;


public record ExecTrendPoint(String date, long passCount, long failCount,
                             long timeoutCount, double passRate) {
}
