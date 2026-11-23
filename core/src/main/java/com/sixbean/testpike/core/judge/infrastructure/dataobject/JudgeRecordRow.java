package com.sixbean.testpike.core.judge.infrastructure.dataobject;


public class JudgeRecordRow {

    private Long id;
    private Long runId;
    private Long sampleId;
    private Integer ruleScore;
    private String ruleHitsJson;
    private String llmScoresJson;
    private Integer finalScore;
    private String reasonsJson;
    private Long latencyMs;
    private Integer tokens;
    private Integer manualFinalScore;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getRunId() {
        return runId;
    }

    public void setRunId(Long runId) {
        this.runId = runId;
    }

    public Long getSampleId() {
        return sampleId;
    }

    public void setSampleId(Long sampleId) {
        this.sampleId = sampleId;
    }

    public Integer getRuleScore() {
        return ruleScore;
    }

    public void setRuleScore(Integer ruleScore) {
        this.ruleScore = ruleScore;
    }

    public String getRuleHitsJson() {
        return ruleHitsJson;
    }

    public void setRuleHitsJson(String ruleHitsJson) {
        this.ruleHitsJson = ruleHitsJson;
    }

    public String getLlmScoresJson() {
        return llmScoresJson;
    }

    public void setLlmScoresJson(String llmScoresJson) {
        this.llmScoresJson = llmScoresJson;
    }

    public Integer getFinalScore() {
        return finalScore;
    }

    public void setFinalScore(Integer finalScore) {
        this.finalScore = finalScore;
    }

    public String getReasonsJson() {
        return reasonsJson;
    }

    public void setReasonsJson(String reasonsJson) {
        this.reasonsJson = reasonsJson;
    }

    public Long getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(Long latencyMs) {
        this.latencyMs = latencyMs;
    }

    public Integer getTokens() {
        return tokens;
    }

    public void setTokens(Integer tokens) {
        this.tokens = tokens;
    }

    public Integer getManualFinalScore() {
        return manualFinalScore;
    }

    public void setManualFinalScore(Integer manualFinalScore) {
        this.manualFinalScore = manualFinalScore;
    }
}
