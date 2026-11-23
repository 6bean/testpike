package com.sixbean.testpike.aiservice.gen.api.dto;

import java.util.List;


public record GeneratedCase(String title, String precondition, String expectedResult,
                            List<GeneratedStep> steps, int priority, int confidence, String endType) {
}
