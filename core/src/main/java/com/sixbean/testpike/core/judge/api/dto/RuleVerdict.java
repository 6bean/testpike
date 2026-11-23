package com.sixbean.testpike.core.judge.api.dto;

import java.util.List;


public record RuleVerdict(Integer ruleScore, List<String> hits, boolean shortCircuited) {
}
