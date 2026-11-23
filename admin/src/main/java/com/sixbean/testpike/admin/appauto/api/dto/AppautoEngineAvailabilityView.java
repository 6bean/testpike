package com.sixbean.testpike.admin.appauto.api.dto;

import java.util.List;


public record AppautoEngineAvailabilityView(
        String code,
        boolean available,
        int onlineCount,
        List<String> missingReasons) {
}
