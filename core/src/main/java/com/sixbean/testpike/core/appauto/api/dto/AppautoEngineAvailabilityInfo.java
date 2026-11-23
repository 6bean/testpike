package com.sixbean.testpike.core.appauto.api.dto;

import java.util.List;


public record AppautoEngineAvailabilityInfo(
        String code,
        boolean available,
        int onlineCount,
        List<String> missingReasons) {
}
