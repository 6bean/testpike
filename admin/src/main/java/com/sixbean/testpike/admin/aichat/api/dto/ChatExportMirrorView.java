package com.sixbean.testpike.admin.aichat.api.dto;

/**
 * Mirror of the core dialog-export view ({@code ChatExportView}, AO batch
 * phase 2): the rendered Markdown already carries core-side masking — this
 * bridge transports it verbatim and never re-strips or re-labels (single
 * source for the masking rule stays where the ledger lives).
 */
public record ChatExportMirrorView(
        long sessionId,
        String title,
        String markdown,
        String filename,
        int turnCount) {
}
