package com.sixbean.testpike.core.judge.infrastructure.dataobject;


public record RecordCardRow(Long recordId, Long sampleId, String input, String expected,
                            String source, Integer ruleScore, String ruleHitsJson,
                            String llmScoresJson, String reasonsJson, Integer finalScore,
                            Integer manualFinalScore, Integer tokens, Long latencyMs) {
}
