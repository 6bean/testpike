package com.sixbean.testpike.core.ai.api.dto;

import java.util.List;

/**
 * Aggregates AI batch generation progress (GET batch progress x/N; status counts plus item details, displayed by frontend polling).
 *
 */
public record AiBatchInfo(long id, long projectId, long operatorId, int total,
                          int pending, int running, int done, int failed, int cancelled,
                          String createdAt, List<AiBatchItemInfo> items,
                          // Three-part queue-waiting notice (null while nothing is
                          // worth telling): reason, current backlog, recovery estimate.
                          String queueNotice) {
}
