package com.sixbean.testpike.core.cluster.api.dto;

import java.util.Map;

/**
 * Installation checkpoint receipt view (protocol 03 step trailing key).
 *
 * @param accepted whether accepted (always true; failures use a human-readable exception rejection; the structural gate reserves an extension slot).
 * @param status token status 0 unused/1 redeemed/2 expired/3 revoked (the node card is switched to pooled accordingly).
 * @param lastStep latest checkpoint projection {step, stepStatus, message, at} (a missing step checkpoint is null and is ledger-only).
 */
public record EnrollHeartbeatView(Boolean accepted, Integer status, Map<String, Object> lastStep) {
}
