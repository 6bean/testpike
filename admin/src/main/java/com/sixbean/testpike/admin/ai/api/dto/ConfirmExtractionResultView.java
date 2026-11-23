package com.sixbean.testpike.admin.ai.api.dto;

import java.util.List;


public record ConfirmExtractionResultView(int createdCount, int skipped, int drafted,
                                          List<Long> createdRequirementIds) {

    /** Legacy two-face call sites keep compiling (drafted = 0, no ids). */
    public ConfirmExtractionResultView(int createdCount, int skipped) {
        this(createdCount, skipped, 0, List.of());
    }
}
