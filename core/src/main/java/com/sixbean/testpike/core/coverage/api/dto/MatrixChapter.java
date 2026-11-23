package com.sixbean.testpike.core.coverage.api.dto;

import java.util.List;


public record MatrixChapter(String chapter, List<MatrixRequirementRow> requirements) {
}
