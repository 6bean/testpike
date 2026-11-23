package com.sixbean.testpike.admin.cluster.api.dto;

import java.util.List;


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
        // Same-name mirror of core's platform wave columns (family + wave
        // position; null for engine/node steps) — bridge maps straight through.
        String componentFamily,
        Integer waveSeq) {
}
