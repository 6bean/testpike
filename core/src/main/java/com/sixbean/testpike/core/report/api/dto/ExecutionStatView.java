package com.sixbean.testpike.core.report.api.dto;


public record ExecutionStatView(java.util.List<ExecTrendPoint> trend,
                                java.util.List<FailDistItem> failDist) {
}
