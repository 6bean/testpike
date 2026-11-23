package com.sixbean.testpike.core.sample.infrastructure.dataobject;

import com.sixbean.testpike.core.common.domain.component.ViewFormats;
import com.sixbean.testpike.core.sample.api.dto.TrainingSampleItem;

import java.sql.Timestamp;


public class TrainingSampleRow {

    private Long id;
    private Long caseId;
    private String caseNo;
    private Long projectId;
    private String sampleType;
    private String sampleJson;
    private Integer sourceVersion;
    private Timestamp createdAt;
    private Integer adopted;

    
    public TrainingSampleItem toInfo() {
        return new TrainingSampleItem(id, caseId, caseNo, projectId, sampleType,
                sampleJson, sourceVersion, ViewFormats.formatTs(createdAt), adopted);
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getCaseId() {
        return caseId;
    }

    public void setCaseId(Long caseId) {
        this.caseId = caseId;
    }

    public String getCaseNo() {
        return caseNo;
    }

    public void setCaseNo(String caseNo) {
        this.caseNo = caseNo;
    }

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public String getSampleType() {
        return sampleType;
    }

    public void setSampleType(String sampleType) {
        this.sampleType = sampleType;
    }

    public String getSampleJson() {
        return sampleJson;
    }

    public void setSampleJson(String sampleJson) {
        this.sampleJson = sampleJson;
    }

    public Integer getSourceVersion() {
        return sourceVersion;
    }

    public void setSourceVersion(Integer sourceVersion) {
        this.sourceVersion = sourceVersion;
    }

    public Timestamp getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Timestamp createdAt) {
        this.createdAt = createdAt;
    }

    public Integer getAdopted() {
        return adopted;
    }

    public void setAdopted(Integer adopted) {
        this.adopted = adopted;
    }
}
