package com.sixbean.testpike.admin.reports.api.dto;

import java.util.List;


public record ExecutionStatView(List<ExecTrendPoint> trend, List<FailDistItem> failDist) {
}
