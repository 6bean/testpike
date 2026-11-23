package com.sixbean.testpike.core.batch.api.dto;

import java.util.List;
import java.util.Map;

/**
 * Batch failure-insight landing input (AO-7 proactive insights, stage 2).
 * <p>Sent by the ai-service clustering consumer through the core internal
 * write-back endpoint — ai-service never touches business tables directly
 * (two-sides separation). batchId rides on the URL path, projectId is
 * server-derived from the batch row at landing time, never from this payload.
 *
 * @param clusters          ranked group list, each entry {class,label,count}
 *                          (class = AI attribution class name or executor
 *                          error-category fallback key)
 * @param unattributedCount failed tasks whose AI attribution had not landed at
 *                          finalize time (clustered by executor error category;
 *                          shown honestly on the insight card)
 * @param failCount         total failed tasks that took part in clustering
 * @param summary           plain-word one-line summary shared by the station
 *                          letter and the report-page card
 */
public record BatchInsightUpsertInput(
        List<Map<String, Object>> clusters,
        Integer unattributedCount,
        Integer failCount,
        String summary) {
}
