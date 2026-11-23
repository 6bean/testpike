package com.sixbean.testpike.core.skill.infrastructure.dataobject;

import com.sixbean.testpike.common.approval.ApprovalStatus;
import com.sixbean.testpike.core.common.domain.component.ViewFormats;
import com.sixbean.testpike.core.skill.api.dto.SkillMarketItem;
import com.sixbean.testpike.core.skill.domain.component.SkillType;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;


public class SkillMarketRow {

    
    static final char TAG_DELIMITER = '\u001F';

    private Long id;
    private Long projectId;
    private String name;
    private Integer type;
    private Integer status;
    private Integer currentVersion;
    private String body;
    private Integer downloadCount;
    private Integer refProjectCount;
    private Integer call30dCount;
    private String authorName;
    private String tagsCsv;
    private Timestamp createdAt;

    
    public SkillMarketItem toInfo() {
        int typeCode = type == null ? 0 : type;
        int statusCode = status == null ? 0 : status;
        return new SkillMarketItem(id, projectId, name, typeCode, SkillType.labelOf(typeCode),
                ApprovalStatus.labelOf(statusCode),
                currentVersion == null ? 0 : currentVersion,
                authorName == null || authorName.isBlank() ? "佚名" : authorName,
                downloadCount == null ? 0 : downloadCount,
                refProjectCount == null ? 0 : refProjectCount,
                call30dCount == null ? 0 : call30dCount,
                splitTags(tagsCsv),
                ViewFormats.preview(body), ViewFormats.formatTs(createdAt));
    }

    private static List<String> splitTags(String csv) {
        List<String> tags = new ArrayList<>();
        if (csv != null && !csv.isEmpty()) {
            for (String part : csv.split(String.valueOf(TAG_DELIMITER))) {
                if (!part.isBlank()) {
                    tags.add(part);
                }
            }
        }
        return tags;
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

    public String getTagsCsv() {
        return tagsCsv;
    }

    public void setTagsCsv(String tagsCsv) {
        this.tagsCsv = tagsCsv;
    }

    public Timestamp getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Timestamp createdAt) {
        this.createdAt = createdAt;
    }
}
