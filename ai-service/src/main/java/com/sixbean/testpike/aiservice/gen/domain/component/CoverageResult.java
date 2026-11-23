package com.sixbean.testpike.aiservice.gen.domain.component;

import java.util.ArrayList;
import java.util.List;


public record CoverageResult(boolean passed, boolean noCriteria, int coveredCount,
                             List<String> uncovered) {
}
