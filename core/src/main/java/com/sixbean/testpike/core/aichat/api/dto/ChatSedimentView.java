package com.sixbean.testpike.core.aichat.api.dto;

/**
 * Result of landing one dialog session into the knowledge base (AO batch
 * phase 2b, E5 sedimentation): the caller gets the document the dialog
 * became, so the UI can point straight at it. {@code documentName} is the
 * display name the document list shows (export file name without the
 * extension, minute stamp included — each save is a point-in-time snapshot);
 * {@code chunkCount} confirms the sync parsing produced searchable pieces.
 */
public record ChatSedimentView(
        long sessionId,
        long documentId,
        String documentName,
        int chunkCount) {
}
