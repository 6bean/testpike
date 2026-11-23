package com.sixbean.testpike.core.judge.infrastructure.dataobject;

import java.sql.Timestamp;

/**
 * Projection row for the rubric family-head enumeration ( segment A; one
 * latest-version row per (name, domain) — only picker columns, the jsonb
 * dimensions/rules payloads deliberately stay out of this row so the option
 * list never drags full rubric bodies across the wire).
 */
public class RubricFamilyRow {

    private Long id;
    private String name;
    private String domain;
    private Integer version;
    private Integer passScore;
    private Integer status;
    private Timestamp createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDomain() {
        return domain;
    }

    public void setDomain(String domain) {
        this.domain = domain;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public Integer getPassScore() {
        return passScore;
    }

    public void setPassScore(Integer passScore) {
        this.passScore = passScore;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Timestamp getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Timestamp createdAt) {
        this.createdAt = createdAt;
    }
}
