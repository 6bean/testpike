package com.sixbean.testpike.core.appauto.infrastructure.dataobject;

import java.time.LocalDateTime;


public class AppautoPlatform {

    private Long id;
    private String code;
    private String name;
    private String deviceKindCodesJson;
    private String carrierTypesJson;
    private String note;
    private int status;
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

    public String getDeviceKindCodesJson() {
        return deviceKindCodesJson;
    }

    public void setDeviceKindCodesJson(String deviceKindCodesJson) {
        this.deviceKindCodesJson = deviceKindCodesJson;
    }

    public String getCarrierTypesJson() {
        return carrierTypesJson;
    }

    public void setCarrierTypesJson(String carrierTypesJson) {
        this.carrierTypesJson = carrierTypesJson;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
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
