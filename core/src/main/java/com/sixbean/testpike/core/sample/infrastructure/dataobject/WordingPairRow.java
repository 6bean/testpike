package com.sixbean.testpike.core.sample.infrastructure.dataobject;

import com.sixbean.testpike.core.common.domain.component.ViewFormats;
import com.sixbean.testpike.core.sample.api.dto.WordingPair;

import java.sql.Timestamp;


public class WordingPairRow {

    private Long rejectSampleId;
    private Long diffSampleId;
    private String remark;
    private String diffChangesJson;
    private Timestamp createdAt;

    
    public WordingPair toInfo() {
        return new WordingPair(rejectSampleId, diffSampleId, remark,
                diffChangesJson, ViewFormats.formatTs(createdAt));
    }

    public Long getRejectSampleId() {
        return rejectSampleId;
    }

    public void setRejectSampleId(Long rejectSampleId) {
        this.rejectSampleId = rejectSampleId;
    }

    public Long getDiffSampleId() {
        return diffSampleId;
    }

    public void setDiffSampleId(Long diffSampleId) {
        this.diffSampleId = diffSampleId;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public String getDiffChangesJson() {
        return diffChangesJson;
    }

    public void setDiffChangesJson(String diffChangesJson) {
        this.diffChangesJson = diffChangesJson;
    }

    public Timestamp getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Timestamp createdAt) {
        this.createdAt = createdAt;
    }
}
