package com.sixbean.testpike.core.prompt.infrastructure.dataobject;

import com.sixbean.testpike.core.prompt.api.dto.PromptTemplateFamilyItem;

import java.sql.Timestamp;
import java.time.format.DateTimeFormatter;

/**
 * Family-row projection for {@link #toInfo()} (one row per (domain, name) family, newest version
 * plus family size). BeanPropertyRowMapper needs settable fields, so this lands as a DO and the
 * service boundary converts to the record.
 */
public class PromptTemplateFamilyRow {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private String domain;
    private String name;
    private Long latestId;
    private Integer latestVersion;
    private Integer latestStatus;
    private Integer versionCount;
    private Timestamp updatedAt;

    public PromptTemplateFamilyItem toInfo() {
        return new PromptTemplateFamilyItem(domain, name, latestId, latestVersion, latestStatus,
                versionCount, updatedAt == null ? null : TS.format(updatedAt.toLocalDateTime()));
    }

    public String getDomain() {
        return domain;
    }

    public void setDomain(String domain) {
        this.domain = domain;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Long getLatestId() {
        return latestId;
    }

    public void setLatestId(Long latestId) {
        this.latestId = latestId;
    }

    public Integer getLatestVersion() {
        return latestVersion;
    }

    public void setLatestVersion(Integer latestVersion) {
        this.latestVersion = latestVersion;
    }

    public Integer getLatestStatus() {
        return latestStatus;
    }

    public void setLatestStatus(Integer latestStatus) {
        this.latestStatus = latestStatus;
    }

    public Integer getVersionCount() {
        return versionCount;
    }

    public void setVersionCount(Integer versionCount) {
        this.versionCount = versionCount;
    }

    public Timestamp getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Timestamp updatedAt) {
        this.updatedAt = updatedAt;
    }
}
