package com.sixbean.testpike.admin.appauto.api.dto;

import java.util.List;


public record AppautoEngineHealthView(
        String code,
        String name,
        boolean enabled,
        int onlineExecutors,
        int readyExecutors,
        List<String> blockingItems,
        boolean aiDoctorEnabled) {
}
