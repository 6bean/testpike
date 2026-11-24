package com.sixbean.testpike.core.session.api.dto;

/**
 * Keep-alive switch payload for one ledger row: the per-row flip plus an
 * optional probe address and a per-row interval override in minutes
 * (null = follow the global cadence key session.keepalive.interval-minutes).
 */
public record LoginStateKeepAliveUpdate(boolean enabled, String url, Integer intervalMinutes) {
}
