package com.sixbean.testpike.admin.aichat.api.dto;

/**
 * Mirror of the core dialog-sediment view ({@code ChatSedimentView}, AO batch
 * phase 2b): the bridge transports the landed document's identity
 * verbatim — no second id mapping and no re-labeling, so the page can point
 * the user straight at the document the conversation became.
 */
public record ChatSedimentMirrorView(
        long sessionId,
        long documentId,
        String documentName,
        int chunkCount) {
}
