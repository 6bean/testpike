package com.sixbean.testpike.core.judge.infrastructure.dataobject;

import java.sql.Timestamp;


public class JudgeSampleRow {

    private Long id;
    private String setName;
    private Integer version;
    private String snapshotDigest;
    private String input;
    private String expected;
    private String referenceDocsJson;
    private String source;
    private Integer sensitive;
    private Long createdBy;
    private Timestamp createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getSetName() {
        return setName;
    }

    public void setSetName(String setName) {
        this.setName = setName;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public String getSnapshotDigest() {
        return snapshotDigest;
    }

    public void setSnapshotDigest(String snapshotDigest) {
        this.snapshotDigest = snapshotDigest;
    }

    public String getInput() {
        return input;
    }

    public void setInput(String input) {
        this.input = input;
    }

    public String getExpected() {
        return expected;
    }

    public void setExpected(String expected) {
        this.expected = expected;
    }

    public String getReferenceDocsJson() {
        return referenceDocsJson;
    }

    public void setReferenceDocsJson(String referenceDocsJson) {
        this.referenceDocsJson = referenceDocsJson;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public Integer getSensitive() {
        return sensitive;
    }

    public void setSensitive(Integer sensitive) {
        this.sensitive = sensitive;
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
}
