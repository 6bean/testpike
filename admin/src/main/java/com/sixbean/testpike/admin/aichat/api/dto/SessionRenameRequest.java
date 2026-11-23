package com.sixbean.testpike.admin.aichat.api.dto;

/**
 * Rename payload of the dialog-session door . The title alone
 * crosses the boundary — the owner id is pinned from the login context in the
 * controller, never accepted from the body (horizontal-access red line).
 */
public record SessionRenameRequest(String title) {
}
