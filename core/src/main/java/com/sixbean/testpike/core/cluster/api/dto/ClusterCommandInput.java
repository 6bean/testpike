package com.sixbean.testpike.core.cluster.api.dto;

import java.util.Map;

/**
 * Cluster command enqueue input (Protocol 03 command queue).
 *
 * @param commandType Command type: engine-install / engine-uninstall / scale-in / node-remove (migration CHECK uses the same set).
 * @param targetType Target type: executor (direct dispatch to pooled machines) / enroll-token (pre-install for machines waiting to join).
 * @param targetId Target row id (executor.id or tp_cluster_enroll_token.id).
 * @param payload Command payload (both engine types must include engines:[descriptor code]; zero credentials — plaintext tokens never enter the command body, redline 4).
 * @param operator Dispatch operator (audit trail).
 */
public record ClusterCommandInput(String commandType, String targetType, Long targetId,
                                  Map<String, Object> payload, String operator) {
}
