package com.sixbean.testpike.core.session.api.dto;

/**
 * Transient run shape for the login-state probe door (11-19 user command:
 * POST + body straight at the target endpoint). Nothing here is stored —
 * the body template lives only for this one replay, and the event trail
 * echoes it with {@code {{CRED:key}}} placeholders intact (resolved values
 * go to the wire, never to the ledger; red line unchanged).
 */
public record ProbeRunInput(String method, String bodyTemplate) {
}
