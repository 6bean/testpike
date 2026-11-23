package com.sixbean.testpike.core.judge.infrastructure.dataobject;

import java.sql.Timestamp;


public class JudgeRunRow {

    private Long id;
    private String rubricName;
    private String rubricDomain;
    private Integer rubricVersion;
    private String rubricSnapshotJson;
    private String evalsetName;
    private Integer evalsetVersion;
    private String evalsetDigest;
    private String targetType;
    private String targetRef;
    private Long llmConfigId;
    private Integer status;
    private Long createdBy;
    private Timestamp createdAt;
    private Timestamp finishedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

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

    public Integer getRubricVersion() {
        return rubricVersion;
    }

    public void setRubricVersion(Integer rubricVersion) {
        this.rubricVersion = rubricVersion;
    }

    public String getRubricSnapshotJson() {
        return rubricSnapshotJson;
    }

    public void setRubricSnapshotJson(String rubricSnapshotJson) {
        this.rubricSnapshotJson = rubricSnapshotJson;
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

    public String getEvalsetDigest() {
        return evalsetDigest;
    }

    public void setEvalsetDigest(String evalsetDigest) {
        this.evalsetDigest = evalsetDigest;
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

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public Timestamp getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Timestamp createdAt) {
        this.createdAt = createdAt;
    }

    public Timestamp getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Timestamp finishedAt) {
        this.finishedAt = finishedAt;
    }
}
