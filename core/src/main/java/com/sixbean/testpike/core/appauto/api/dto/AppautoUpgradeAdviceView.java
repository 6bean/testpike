package com.sixbean.testpike.core.appauto.api.dto;

import java.util.List;


public record AppautoUpgradeAdviceView(
        List<String> risks,
        String summary,
        List<String> breakingChanges,
        AppautoCaseFeatureProfile profile,
        String conclusion) {
}
