package com.sixbean.testpike.aiservice.judge.infrastructure.remote;

import java.util.List;
import java.util.Map;


public record JudgeRecordWrite(Long runId, Long sampleId, Integer ruleScore,
                               List<String> ruleHits, Map<String, Double> llmScores,
                               Map<String, String> reasons, Long latencyMs, Integer tokens) {

    
    public Map<String, Object> toRequestBody() {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("runId", runId);
        body.put("sampleId", sampleId);
        body.put("ruleScore", ruleScore);
        body.put("ruleHits", ruleHits);
        body.put("llmScores", llmScores);
        body.put("reasons", reasons);
        body.put("latencyMs", latencyMs);
        body.put("tokens", tokens);
        return body;
    }
}
