package com.sixbean.testpike.core.appauto.api.dto;

import java.util.List;


public record AppautoDeviceInput(
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

 
    public AppautoDeviceInput(String name, String serial, String deviceType, String adbAddress,
                              String model, String osVersion, String resolution, Integer dpi,
                              List<String> labels) {
        this(name, serial, deviceType, adbAddress, model, osVersion, resolution, dpi, labels, null);
    }
}
