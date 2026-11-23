package com.sixbean.testpike.core.appauto.api.dto;

import java.util.List;


public record AppautoEngineHealthInfo(
        String code,
        String name,
        boolean enabled,
        int onlineExecutors,
        int readyExecutors,
        List<String> blockingItems,
        boolean aiDoctorEnabled) {
}
