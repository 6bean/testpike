package com.sixbean.testpike.core.judge.api.dto;

import java.math.BigDecimal;
import java.util.List;


public record RubricSaveInput(String name, String domain, List<RubricDimension> dimensions,
                              List<JudgeRuleItem> rules, int passScore,
                              BigDecimal wRule, BigDecimal wLlm, Long createdBy) {
}
