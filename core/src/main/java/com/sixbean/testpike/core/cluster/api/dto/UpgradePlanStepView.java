package com.sixbean.testpike.core.cluster.api.dto;


public record UpgradePlanStepView(
        Long id,
        Long planId,
        String nodeId,
        String kind,
        String engineCode,
        String phase,
        String currentVersion,
        String targetVersion,
        String status,
        Long commandId,
        String evidence,
        // Platform wave columns : which family this step swaps and its
        // position in the dependency-derived wave order. NULL for engine/node
        // steps. Consumers order/sort on the persisted wave_seq — re-deriving
        // the order anywhere would be a second spectrum (banned).
        String componentFamily,
        Integer waveSeq) {
}
