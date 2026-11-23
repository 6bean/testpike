package com.sixbean.testpike.core.knowledge.infrastructure.dataobject;

import com.sixbean.testpike.core.common.domain.component.ViewFormats;
import com.sixbean.testpike.core.knowledge.api.dto.KnowledgeDocItem;

import java.sql.Timestamp;


public class KnowledgeDocRow {

    private Long id;
    private Long projectId;
    private Long packId;
    private String sourceType;
    private Long sourceRef;
    private String title;
    private String content;
    private String status;
    private Integer revision;
    private Timestamp createdAt;
    private Timestamp updatedAt;

    
    public KnowledgeDocItem toInfo() {
        return new KnowledgeDocItem(id, projectId, packId, sourceType, sourceRef,
                title, content, status,
                ViewFormats.formatTs(createdAt), ViewFormats.formatTs(updatedAt), revision);
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public Long getPackId() {
        return packId;
    }

    public void setPackId(Long packId) {
        this.packId = packId;
    }

    public String getSourceType() {
        return sourceType;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }

    public Long getSourceRef() {
        return sourceRef;
    }

    public void setSourceRef(Long sourceRef) {
        this.sourceRef = sourceRef;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getRevision() {
        return revision;
    }

    public void setRevision(Integer revision) {
        this.revision = revision;
    }

    public Timestamp getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Timestamp createdAt) {
        this.createdAt = createdAt;
    }

    public Timestamp getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Timestamp updatedAt) {
        this.updatedAt = updatedAt;
    }
}
