package com.sixbean.testpike.aiservice.judge.domain.component;

import java.util.List;
import java.util.Map;


public record JudgeVerdict(Map<String, Double> llmScores, Map<String, String> reasons,
                           List<String> orphanDimensions) {
}
