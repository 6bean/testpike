package com.sixbean.testpike.core.judge.infrastructure.dataobject;

import java.time.LocalDateTime;


public class ReviewQueueRow {

    private Long recordId;
    private Long runId;
    private Long sampleId;
    private Integer ruleScore;
    private Integer finalScore;
    private String llmScoresJson;
    private String reasonsJson;
    private String rubricSnapshotJson;
    private LocalDateTime createdAt;

    public Long getRecordId() {
        return recordId;
    }

    public void setRecordId(Long recordId) {
        this.recordId = recordId;
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

    public Integer getFinalScore() {
        return finalScore;
    }

    public void setFinalScore(Integer finalScore) {
        this.finalScore = finalScore;
    }

    public String getLlmScoresJson() {
        return llmScoresJson;
    }

    public void setLlmScoresJson(String llmScoresJson) {
        this.llmScoresJson = llmScoresJson;
    }

    public String getReasonsJson() {
        return reasonsJson;
    }

    public void setReasonsJson(String reasonsJson) {
        this.reasonsJson = reasonsJson;
    }

    public String getRubricSnapshotJson() {
        return rubricSnapshotJson;
    }

    public void setRubricSnapshotJson(String rubricSnapshotJson) {
        this.rubricSnapshotJson = rubricSnapshotJson;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
