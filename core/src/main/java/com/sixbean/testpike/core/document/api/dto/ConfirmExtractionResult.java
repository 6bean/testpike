package com.sixbean.testpike.core.document.api.dto;

import java.util.List;


public record ConfirmExtractionResult(int createdCount, int skipped, int drafted,
                                      List<Long> createdRequirementIds) {

    /** Legacy call sites without the id-list dimension keep compiling (empty list). */
    public ConfirmExtractionResult(int createdCount, int skipped, int drafted) {
        this(createdCount, skipped, drafted, List.of());
    }

    /** Legacy call sites without the proposal dimension keep compiling (drafted = 0). */
    public ConfirmExtractionResult(int createdCount, int skipped) {
        this(createdCount, skipped, 0, List.of());
    }
}
