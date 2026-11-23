package com.sixbean.testpike.core.judge.api.dto;

import java.math.BigDecimal;
import java.util.List;


public record RubricView(Long id, String name, String domain, Integer version,
                         List<RubricDimension> dimensions, List<JudgeRuleItem> rules,
                         Integer passScore, BigDecimal wRule, BigDecimal wLlm,
                         Integer status, Long createdBy, String createdAt) {
}
