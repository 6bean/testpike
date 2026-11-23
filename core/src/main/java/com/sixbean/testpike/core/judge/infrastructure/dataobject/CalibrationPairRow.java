package com.sixbean.testpike.core.judge.infrastructure.dataobject;


public class CalibrationPairRow {

    private String rubricName;
    private String rubricDomain;
    private Long llmConfigId;
    private Integer orgScore;
    private Integer newScore;

    public String getRubricName() {
        return rubricName;
    }

    public void setRubricName(String rubricName) {
        this.rubricName = rubricName;
    }

    public String getRubricDomain() {
        return rubricDomain;
    }

    public void setRubricDomain(String rubricDomain) {
        this.rubricDomain = rubricDomain;
    }

    public Long getLlmConfigId() {
        return llmConfigId;
    }

    public void setLlmConfigId(Long llmConfigId) {
        this.llmConfigId = llmConfigId;
    }

    public Integer getOrgScore() {
        return orgScore;
    }

    public void setOrgScore(Integer orgScore) {
        this.orgScore = orgScore;
    }

    public Integer getNewScore() {
        return newScore;
    }

    public void setNewScore(Integer newScore) {
        this.newScore = newScore;
    }
}
