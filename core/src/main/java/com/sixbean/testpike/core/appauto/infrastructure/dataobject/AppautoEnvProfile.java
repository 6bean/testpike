package com.sixbean.testpike.core.appauto.infrastructure.dataobject;

import java.time.LocalDateTime;


public class AppautoEnvProfile {

    private Long id;
    private Long projectId;
    private String name;
    private String profileKind;
    private String payloadJson;
    private Long loginRecipeAssetId;

    public Long getLoginRecipeAssetId() {
        return loginRecipeAssetId;
    }

    public void setLoginRecipeAssetId(Long loginRecipeAssetId) {
        this.loginRecipeAssetId = loginRecipeAssetId;
    }
    private String remark;
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

    public String getProfileKind() {
        return profileKind;
    }

    public void setProfileKind(String profileKind) {
        this.profileKind = profileKind;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public void setPayloadJson(String payloadJson) {
        this.payloadJson = payloadJson;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
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
