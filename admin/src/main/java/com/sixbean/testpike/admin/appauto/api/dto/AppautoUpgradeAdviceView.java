package com.sixbean.testpike.admin.appauto.api.dto;

import java.util.List;
import java.util.Map;


public record AppautoUpgradeAdviceView(
        List<String> risks,
        String summary,
        List<String> breakingChanges,
        Map<String, Object> profile,
        String conclusion) {
}
