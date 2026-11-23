package com.sixbean.testpike.core.aichat.api.dto;

/**
 * The frozen copy a public share link serves . Deliberately
 * holds no ids: neither the session key nor the owner id may ride out through
 * an unauthenticated door — an id on this face would invite enumeration
 * against the rest of the system. {@code markdown} is the snapshot as it was
 * rendered at share-creation time (masked by the export pipeline, system
 * plumbing dropped, unsealed turns excluded); later session traffic never
 * changes it. {@code expiresAt} lets the page tell the holder when the link
 * stops working.
 */
public record ChatShareSnapshotView(
        String title,
        String markdown,
        int turnCount,
        java.time.LocalDateTime expiresAt) {
}
