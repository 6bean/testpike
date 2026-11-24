package com.sixbean.testpike.admin.session.api.dto;

/**
 * Keep-alive switch payload for one login-state ledger row (admin side of the
 * bridge). The per-row flip optionally carries a probe address and an
 * interval override in minutes; null interval follows the global cadence
 * key. Enabling runs the synchronous double pre-check inside core — refusals
 * propagate here verbatim, this layer adds no second validation.
 */
public record LoginStateKeepAliveRequest(boolean enabled, String url, Integer intervalMinutes) {
}
