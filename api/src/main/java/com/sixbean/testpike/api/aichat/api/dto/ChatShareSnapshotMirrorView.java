package com.sixbean.testpike.api.aichat.api.dto;

/**
 * Mirror of the core public share view ({@code ChatShareSnapshotView}, AO
 * batch P2): the frozen copy one public link serves. Deliberately holds
 * no ids — neither the session key nor the owner id may ride out through an
 * unauthenticated door. The api leg transports it verbatim; masking and the
 * anti-enumeration sentence stay single-sourced in core.
 */
public record ChatShareSnapshotMirrorView(
        String title,
        String markdown,
        int turnCount,
        java.time.LocalDateTime expiresAt) {
}
