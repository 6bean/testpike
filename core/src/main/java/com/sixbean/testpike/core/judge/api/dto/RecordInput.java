package com.sixbean.testpike.core.judge.api.dto;

import java.util.List;
import java.util.Map;


public record RecordInput(Long runId, Long sampleId, Integer ruleScore,
                          List<String> ruleHits, Map<String, Double> llmScores,
                          Map<String, String> reasons, Long latencyMs, Integer tokens) {
}
