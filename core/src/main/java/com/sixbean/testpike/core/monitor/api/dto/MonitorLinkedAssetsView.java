package com.sixbean.testpike.core.monitor.api.dto;

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

    
    public static final int MAX_NAMES = 50;

    
    public static MonitorLinkedAssetsView empty(long envId) {
        return new MonitorLinkedAssetsView(envId, 0, List.of(), 0, List.of(),
                0, List.of(), 0, List.of());
    }
}
