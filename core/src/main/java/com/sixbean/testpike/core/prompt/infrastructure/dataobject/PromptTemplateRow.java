package com.sixbean.testpike.core.prompt.infrastructure.dataobject;

import com.sixbean.testpike.core.common.domain.component.ViewFormats;
import com.sixbean.testpike.core.prompt.api.dto.PromptTemplateItem;

import java.sql.Timestamp;


public class PromptTemplateRow {

    private Long id;
    private String name;
    private String domain;
    private Integer version;
    private String content;
    private Integer status;
    private Long createdBy;
    private Long rollbackTo;
    private Timestamp createdAt;

    
    public PromptTemplateItem toInfo() {
        return new PromptTemplateItem(id, name, domain, version, content, status,
                createdBy, rollbackTo, ViewFormats.formatTs(createdAt));
    }

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

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
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

    public Long getRollbackTo() {
        return rollbackTo;
    }

    public void setRollbackTo(Long rollbackTo) {
        this.rollbackTo = rollbackTo;
    }

    public Timestamp getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Timestamp createdAt) {
        this.createdAt = createdAt;
    }
}
