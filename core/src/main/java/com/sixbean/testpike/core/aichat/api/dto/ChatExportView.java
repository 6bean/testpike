package com.sixbean.testpike.core.aichat.api.dto;

/**
 * Markdown rendering of one dialog session (AO batch phase 2, E5
 * sedimentation). {@code markdown} is already masked — no credential shape,
 * token value or phone/email leaves the door in cleartext; {@code filename} is
 * the download name the browser saves under; {@code turnCount} is the number
 * of question turns the reader sees (system plumbing rows never count).
 */
public record ChatExportView(
        long sessionId,
        String title,
        String markdown,
        String filename,
        int turnCount) {
}
