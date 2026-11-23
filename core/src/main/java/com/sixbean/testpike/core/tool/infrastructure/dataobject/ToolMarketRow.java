package com.sixbean.testpike.core.tool.infrastructure.dataobject;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.approval.ApprovalStatus;
import com.sixbean.testpike.core.common.domain.component.ViewFormats;
import com.sixbean.testpike.core.tool.api.dto.ToolMarketItem;
import com.sixbean.testpike.core.tool.domain.component.ToolType;

import java.sql.Timestamp;
import java.util.List;


public class ToolMarketRow {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Long id;
    private Long projectId;
    private String name;
    private Integer type;
    private String category;
    private Integer status;
    private Integer currentVersion;
    private String body;
    private String tagsJson;
    private Integer downloadCount;
    private Integer refProjectCount;
    private Integer call30dCount;
    private String authorName;
    private Timestamp createdAt;

    
    public ToolMarketItem toInfo() {
        int typeCode = type == null ? 0 : type;
        int statusCode = status == null ? 0 : status;
        return new ToolMarketItem(id, projectId, name, typeCode, ToolType.labelOf(typeCode),
                category, ApprovalStatus.labelOf(statusCode),
                currentVersion == null ? 0 : currentVersion,
                authorName == null || authorName.isBlank() ? "佚名" : authorName,
                downloadCount == null ? 0 : downloadCount,
                refProjectCount == null ? 0 : refProjectCount,
                call30dCount == null ? 0 : call30dCount,
                parseTags(tagsJson),
                ViewFormats.preview(body), ViewFormats.formatTs(createdAt));
    }

    
    private static List<String> parseTags(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> parsed = MAPPER.readValue(json, new TypeReference<>() {
            });
            return parsed == null ? List.of() : parsed.stream().filter(t -> t != null && !t.isBlank())
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getType() {
        return type;
    }

    public void setType(Integer type) {
        this.type = type;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Integer getCurrentVersion() {
        return currentVersion;
    }

    public void setCurrentVersion(Integer currentVersion) {
        this.currentVersion = currentVersion;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public String getTagsJson() {
        return tagsJson;
    }

    public void setTagsJson(String tagsJson) {
        this.tagsJson = tagsJson;
    }

    public Integer getDownloadCount() {
        return downloadCount;
    }

    public void setDownloadCount(Integer downloadCount) {
        this.downloadCount = downloadCount;
    }

    public Integer getRefProjectCount() {
        return refProjectCount;
    }

    public void setRefProjectCount(Integer refProjectCount) {
        this.refProjectCount = refProjectCount;
    }

    public Integer getCall30dCount() {
        return call30dCount;
    }

    public void setCall30dCount(Integer call30dCount) {
        this.call30dCount = call30dCount;
    }

    public String getAuthorName() {
        return authorName;
    }

    public void setAuthorName(String authorName) {
        this.authorName = authorName;
    }

    public Timestamp getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Timestamp createdAt) {
        this.createdAt = createdAt;
    }
}
