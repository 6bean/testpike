package com.sixbean.testpike.core.appauto.infrastructure.dataobject;

import java.time.LocalDateTime;


public class AppautoEngine {

    private Long id;
    private String code;
    private String name;
    private String platformCodesJson;
    private String deviceKindCodesJson;
    private int defaultPriority;
    private String runtimeType;
    private String runtimeVersionReqJson;
    private String capabilitiesJson;
    private String driverRequirementsJson;
    private String envCheckSpecJson;
    private String installRecipeJson;
    private int status;
    private String installDocUrl;
    private String license;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPlatformCodesJson() {
        return platformCodesJson;
    }

    public void setPlatformCodesJson(String platformCodesJson) {
        this.platformCodesJson = platformCodesJson;
    }

    public String getDeviceKindCodesJson() {
        return deviceKindCodesJson;
    }

    public void setDeviceKindCodesJson(String deviceKindCodesJson) {
        this.deviceKindCodesJson = deviceKindCodesJson;
    }

    public int getDefaultPriority() {
        return defaultPriority;
    }

    public void setDefaultPriority(int defaultPriority) {
        this.defaultPriority = defaultPriority;
    }

    public String getRuntimeType() {
        return runtimeType;
    }

    public void setRuntimeType(String runtimeType) {
        this.runtimeType = runtimeType;
    }

    public String getRuntimeVersionReqJson() {
        return runtimeVersionReqJson;
    }

    public void setRuntimeVersionReqJson(String runtimeVersionReqJson) {
        this.runtimeVersionReqJson = runtimeVersionReqJson;
    }

    public String getCapabilitiesJson() {
        return capabilitiesJson;
    }

    public void setCapabilitiesJson(String capabilitiesJson) {
        this.capabilitiesJson = capabilitiesJson;
    }

    public String getDriverRequirementsJson() {
        return driverRequirementsJson;
    }

    public void setDriverRequirementsJson(String driverRequirementsJson) {
        this.driverRequirementsJson = driverRequirementsJson;
    }

    public String getEnvCheckSpecJson() {
        return envCheckSpecJson;
    }

    public void setEnvCheckSpecJson(String envCheckSpecJson) {
        this.envCheckSpecJson = envCheckSpecJson;
    }

    public String getInstallRecipeJson() {
        return installRecipeJson;
    }

    public void setInstallRecipeJson(String installRecipeJson) {
        this.installRecipeJson = installRecipeJson;
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    public String getInstallDocUrl() {
        return installDocUrl;
    }

    public void setInstallDocUrl(String installDocUrl) {
        this.installDocUrl = installDocUrl;
    }

    public String getLicense() {
        return license;
    }

    public void setLicense(String license) {
        this.license = license;
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
