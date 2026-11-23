package com.sixbean.testpike.core.appauto.api.dto;

import java.util.List;


public record AppautoSupportView(
        String engineCode,
        String engineName,
        boolean enabled,
        String versionState,
        String versionReason,
        String healthState,
        int healthSamples,
        double healthPassRate,
        List<AppautoHealthGroupView> healthGroups,
        String conclusion,
        String caliberNote) {
}
