package com.sixbean.testpike.admin.cluster.api.dto;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Cluster command view mirrored field-by-field from the core record of the same name
 * (protocol 03 read side). Admin re-derives nothing: the numeric status keeps the core
 * value domain (0 pending dispatch, 1 claimed, 2 executing, 3 succeeded, 4 failed,
 * 5 revoked) and null progress/receipt means honest absence, never a defaulted claim.
 *
 * @param id command id.
 * @param commandType command type (four-value domain).
 * @param targetType target type (executor / enroll-token).
 * @param targetId target row id.
 * @param payload command payload (Map; malformed JSON already degraded to empty on the core side).
 * @param status 0 pending dispatch, 1 claimed, 2 executing, 3 succeeded, 4 failed, 5 revoked.
 * @param claimedBy claiming executor id (null if not claimed).
 * @param lastProgress latest progress projection {step,message,at} (null if not advanced).
 * @param resultDetail receipt human-readable details (<=2000).
 * @param expiresAt pending-dispatch expiration endpoint (null when the row carries no deadline).
 * @param createdAt enqueue time.
 */
public record ClusterCommandView(Long id, String commandType, String targetType, Long targetId,
                                 Map<String, Object> payload, Integer status, Long claimedBy,
                                 Map<String, Object> lastProgress, String resultDetail,
                                 LocalDateTime expiresAt, LocalDateTime createdAt) {
}
