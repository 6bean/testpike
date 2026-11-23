package com.sixbean.testpike.aiservice.upgradeadvisor.api.dto;

import java.util.List;


public record UpgradeAdvisorRequest(
        Long projectId,
        String manifestVersion,
        List<String> breakingChanges,
        UpgradeAdvisorProfile profile) {
}
