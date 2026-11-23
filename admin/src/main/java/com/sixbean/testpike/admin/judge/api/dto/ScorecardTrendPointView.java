package com.sixbean.testpike.admin.judge.api.dto;


public record ScorecardTrendPointView(Long runId, Integer rubricVersion, String evalsetName,
                                      Integer evalsetVersion, String finishedAt, Integer scored,
                                      Integer avgFinalScore, Double passRate, Long tokensTotal) {
}
