package com.sixbean.testpike.core.judge.api.dto;

import java.util.Map;


public record JudgeRuleItem(String checker, Map<String, Object> params) {
}
