package com.sixbean.testpike.core.cluster.api.dto;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Cluster command view (protocol 03; shared by poll claim projection and the management-plane read side).
 *
 * @param id command id.
 * @param commandType command type (four-value domain).
 * @param targetType target type (executor / enroll-token).
 * @param targetId target row id.
 * @param payload command payload (Map: malformed JSON falls back to an empty map; honest absence must not crash the observation surface).
 * @param status 0 pending dispatch, 1 claimed, 2 executing, 3 succeeded, 4 failed, 5 revoked.
 * @param claimedBy claiming executor id (null if not claimed).
 * @param lastProgress latest progress projection {step,message,at} (null if not advanced).
 * @param resultDetail receipt human-readable details (≤2000).
 * @param expiresAt pending-dispatch expiration endpoint (if unclaimed after timeout, poll filters and rejects the claim).
 * @param createdAt enqueue time.
 */
public record ClusterCommandView(Long id, String commandType, String targetType, Long targetId,
                                 Map<String, Object> payload, Integer status, Long claimedBy,
                                 Map<String, Object> lastProgress, String resultDetail,
                                 LocalDateTime expiresAt, LocalDateTime createdAt) {
}
