package com.sixbean.testpike.core.judge.api.dto;

import java.util.List;


public record JudgeRuleCheckInput(List<JudgeRuleItem> rules, String output, String expected) {
}
