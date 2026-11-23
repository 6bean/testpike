package com.sixbean.testpike.admin.judge.api.dto;

import java.util.List;
import java.util.Map;


public record RunSampleCardView(Long recordId, Long sampleId, String inputPreview,
                                String expectedPreview, String sampleSource,
                                Integer ruleScore, List<String> ruleHits,
                                Map<String, Object> llmScores, Map<String, Object> reasons,
                                Integer machineFinalScore, Integer manualFinalScore,
                                Integer authoritativeScore, boolean reviewed,
                                Integer tokens, Long latencyMs) {
}
