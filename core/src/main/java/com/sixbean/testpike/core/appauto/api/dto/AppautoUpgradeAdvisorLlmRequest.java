package com.sixbean.testpike.core.appauto.api.dto;

import java.util.List;


public record AppautoUpgradeAdvisorLlmRequest(
        Long projectId,
        String manifestVersion,
        List<String> breakingChanges,
        AppautoCaseFeatureProfile profile) {
}
