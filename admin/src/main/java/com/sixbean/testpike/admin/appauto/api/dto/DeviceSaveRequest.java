package com.sixbean.testpike.admin.appauto.api.dto;

import java.util.List;


public record DeviceSaveRequest(
        Long projectId,
        String name,
        String serial,
        String deviceType,
        String adbAddress,
        String model,
        String osVersion,
        String resolution,
        Integer dpi,
        List<String> labels,
        String platform) {

 
    public DeviceSaveRequest(Long projectId, String name, String serial, String deviceType,
                             String adbAddress, String model, String osVersion, String resolution,
                             Integer dpi, List<String> labels) {
        this(projectId, name, serial, deviceType, adbAddress, model, osVersion, resolution,
                dpi, labels, null);
    }
}
