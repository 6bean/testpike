package com.sixbean.testpike.core.appauto.infrastructure.dataobject;

import java.time.LocalDateTime;


public class AppautoApp {

    private Long id;
    private Long projectId;
    private String name;
    private String packageName;
    private String version;
    private String platformCode;
    private String permissionRuleRef;
    private String apkArtifactKey;
    private Long artifactSizeBytes;
    private String artifactSha256;
    private String source;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

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

    public String getPackageName() {
        return packageName;
    }

    public void setPackageName(String packageName) {
        this.packageName = packageName;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getPlatformCode() {
        return platformCode;
    }

    public void setPlatformCode(String platformCode) {
        this.platformCode = platformCode;
    }

    public String getPermissionRuleRef() {
        return permissionRuleRef;
    }

    public void setPermissionRuleRef(String permissionRuleRef) {
        this.permissionRuleRef = permissionRuleRef;
    }

    public String getApkArtifactKey() {
        return apkArtifactKey;
    }

    public void setApkArtifactKey(String apkArtifactKey) {
        this.apkArtifactKey = apkArtifactKey;
    }

    public Long getArtifactSizeBytes() {
        return artifactSizeBytes;
    }

    public void setArtifactSizeBytes(Long artifactSizeBytes) {
        this.artifactSizeBytes = artifactSizeBytes;
    }

    public String getArtifactSha256() {
        return artifactSha256;
    }

    public void setArtifactSha256(String artifactSha256) {
        this.artifactSha256 = artifactSha256;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
