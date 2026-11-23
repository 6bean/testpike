package com.sixbean.testpike.aiservice.upgradeadvisor.api.dto;

import java.util.Map;


public record UpgradeAdvisorProfile(
        int scriptCases,
        Map<String, Integer> verbCounts,
        Map<String, Integer> engineUsage,
        int aiSampleCases,
        int webviewSteps) {
}
