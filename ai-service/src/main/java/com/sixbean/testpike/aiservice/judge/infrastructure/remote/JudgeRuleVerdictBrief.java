package com.sixbean.testpike.aiservice.judge.infrastructure.remote;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;


@JsonIgnoreProperties(ignoreUnknown = true)
public record JudgeRuleVerdictBrief(Integer ruleScore, List<String> hits,
                                    boolean shortCircuited) {
}
