package com.sixbean.testpike.admin.monitor.api.dto;

import java.util.List;


public record MonitorLinkedAssetsView(
        long envId,
        int planCount,
        List<String> planNames,
        int suiteCount,
        List<String> suiteNames,
        int factoryCount,
        List<String> factoryNames,
        int apiRequestCount,
        List<String> apiRequestNames) {
}
