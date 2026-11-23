package com.sixbean.testpike.admin.coverage.api.dto;

import java.util.List;


public record MatrixChapter(String chapter, List<MatrixRequirementRow> requirements) {
}
