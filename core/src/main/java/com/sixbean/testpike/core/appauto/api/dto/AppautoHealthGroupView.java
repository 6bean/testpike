package com.sixbean.testpike.core.appauto.api.dto;

import java.util.Map;


public record AppautoHealthGroupView(
        String platform,
        int samples,
        int passCount,
        double passRate,
        Map<String, Integer> errorTypeDist,
        Map<String, Integer> aiFailureDist) {
}
