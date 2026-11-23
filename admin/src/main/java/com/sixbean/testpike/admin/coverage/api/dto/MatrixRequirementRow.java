package com.sixbean.testpike.admin.coverage.api.dto;

import java.util.List;


public record MatrixRequirementRow(long id, String title, boolean covered, List<Long> caseIds) {
}
