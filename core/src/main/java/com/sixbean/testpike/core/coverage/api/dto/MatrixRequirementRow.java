package com.sixbean.testpike.core.coverage.api.dto;

import java.util.List;


public record MatrixRequirementRow(long id, String title, boolean covered, List<Long> caseIds) {
}
