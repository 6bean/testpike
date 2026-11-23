package com.sixbean.testpike.admin.coverage.api.dto;

import java.util.List;


public record CoverageMatrixView(List<MatrixChapter> chapters, List<MatrixCaseColumn> cases,
                                 long totalRequirements, long coveredRequirements,
                                 List<Long> uncoveredRequirementIds) {
}
