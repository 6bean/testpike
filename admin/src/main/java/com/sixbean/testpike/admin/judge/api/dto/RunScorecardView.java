package com.sixbean.testpike.admin.judge.api.dto;

import java.util.Map;


public record RunScorecardView(Long runId, String rubricName, String rubricDomain,
                               Integer rubricVersion, String evalsetName,
                               Integer evalsetVersion, Integer status, Integer passScore,
                               Integer sampleTotal, Integer scored, Integer avgFinalScore,
                               Integer passCount, Double passRate, Long tokensTotal,
                               Double latencyAvgMs, Map<String, Double> dimensionAverages) {
}
