package com.sixbean.testpike.admin.session.api.dto;

/**
 * Admin mirror of the core probe run shape (POST /session-states/{id}/probe
 * optional payload). Everything is transient: the body template is never
 * stored on the ledger and never echoed back — the trail keeps the template
 * source with {{CRED:key}} placeholders intact.
 */
public record LoginStateProbeRunRequest(String method, String bodyTemplate) {
}
