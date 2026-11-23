package com.sixbean.testpike.admin.appauto.api.dto;

import java.util.List;
import java.util.Map;


public record AppautoSupportView(
        String engineCode,
        String engineName,
        boolean enabled,
        String versionState,
        String versionReason,
        String healthState,
        int healthSamples,
        double healthPassRate,
        List<Map<String, Object>> healthGroups,
        String conclusion,
        String caliberNote) {
}
