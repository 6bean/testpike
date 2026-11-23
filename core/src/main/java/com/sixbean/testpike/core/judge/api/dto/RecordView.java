package com.sixbean.testpike.core.judge.api.dto;

import java.util.List;
import java.util.Map;


public record RecordView(Long id, Long runId, Long sampleId, Integer ruleScore,
                         List<String> ruleHits, Map<String, Object> llmScores,
                         Integer finalScore, Map<String, Object> reasons,
                         Long latencyMs, Integer tokens, Integer manualFinalScore) {
}
