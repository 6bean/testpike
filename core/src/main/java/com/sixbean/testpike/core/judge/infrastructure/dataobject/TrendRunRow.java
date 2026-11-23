package com.sixbean.testpike.core.judge.infrastructure.dataobject;

import java.sql.Timestamp;


public class TrendRunRow {

    private Long id;
    private Integer rubricVersion;
    private String evalsetName;
    private Integer evalsetVersion;
    private String rubricSnapshotJson;
    private Timestamp finishedAt;
    private String targetType;
    private String targetRef;
    private Long llmConfigId;
    private String llmProvider;
    private String llmModelName;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Integer getRubricVersion() {
        return rubricVersion;
    }

    public void setRubricVersion(Integer rubricVersion) {
        this.rubricVersion = rubricVersion;
    }

    public String getEvalsetName() {
        return evalsetName;
    }

    public void setEvalsetName(String evalsetName) {
        this.evalsetName = evalsetName;
    }

    public Integer getEvalsetVersion() {
        return evalsetVersion;
    }

    public void setEvalsetVersion(Integer evalsetVersion) {
        this.evalsetVersion = evalsetVersion;
    }

    public String getRubricSnapshotJson() {
        return rubricSnapshotJson;
    }

    public void setRubricSnapshotJson(String rubricSnapshotJson) {
        this.rubricSnapshotJson = rubricSnapshotJson;
    }

    public Timestamp getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Timestamp finishedAt) {
        this.finishedAt = finishedAt;
    }

    public String getTargetType() {
        return targetType;
    }

    public void setTargetType(String targetType) {
        this.targetType = targetType;
    }

    public String getTargetRef() {
        return targetRef;
    }

    public void setTargetRef(String targetRef) {
        this.targetRef = targetRef;
    }

    public Long getLlmConfigId() {
        return llmConfigId;
    }

    public void setLlmConfigId(Long llmConfigId) {
        this.llmConfigId = llmConfigId;
    }

    public String getLlmProvider() {
        return llmProvider;
    }

    public void setLlmProvider(String llmProvider) {
        this.llmProvider = llmProvider;
    }

    public String getLlmModelName() {
        return llmModelName;
    }

    public void setLlmModelName(String llmModelName) {
        this.llmModelName = llmModelName;
    }

    
    public String judgeModel() {
        return llmProvider == null || llmProvider.isBlank()
                || llmModelName == null || llmModelName.isBlank()
                ? null : llmProvider + "/" + llmModelName;
    }
}
