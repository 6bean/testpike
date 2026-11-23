package com.sixbean.testpike.core.judge.infrastructure.dataobject;

import java.math.BigDecimal;
import java.sql.Timestamp;


public class JudgeRubricRow {

    private Long id;
    private String name;
    private String domain;
    private Integer version;
    private String dimensionsJson;
    private String rulesJson;
    private Integer passScore;
    private BigDecimal wRule;
    private BigDecimal wLlm;
    private Integer status;
    private Long createdBy;
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

    public String getDimensionsJson() {
        return dimensionsJson;
    }

    public void setDimensionsJson(String dimensionsJson) {
        this.dimensionsJson = dimensionsJson;
    }

    public String getRulesJson() {
        return rulesJson;
    }

    public void setRulesJson(String rulesJson) {
        this.rulesJson = rulesJson;
    }

    public Integer getPassScore() {
        return passScore;
    }

    public void setPassScore(Integer passScore) {
        this.passScore = passScore;
    }

    public BigDecimal getWRule() {
        return wRule;
    }

    public void setWRule(BigDecimal wRule) {
        this.wRule = wRule;
    }

    public BigDecimal getWLlm() {
        return wLlm;
    }

    public void setWLlm(BigDecimal wLlm) {
        this.wLlm = wLlm;
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
}
