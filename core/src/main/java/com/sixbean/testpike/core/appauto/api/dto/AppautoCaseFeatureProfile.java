package com.sixbean.testpike.core.appauto.api.dto;

import java.util.Map;


public record AppautoCaseFeatureProfile(
        int scriptCases,
        Map<String, Integer> verbCounts,
        Map<String, Integer> engineUsage,
        int aiSampleCases,
        int webviewSteps) {
}
