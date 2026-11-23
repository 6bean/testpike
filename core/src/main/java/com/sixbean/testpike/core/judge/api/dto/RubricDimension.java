package com.sixbean.testpike.core.judge.api.dto;

import java.util.List;


public record RubricDimension(String name, String definition, Double weight,
                              List<String> anchorExamples) {
}
