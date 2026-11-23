package com.sixbean.testpike.core.ai.domain.service;

import com.sixbean.testpike.core.ai.api.dto.AiBatchCreateInput;
import com.sixbean.testpike.core.ai.api.dto.AiBatchCreateResult;
import com.sixbean.testpike.core.ai.api.dto.AiBatchEstimate;
import com.sixbean.testpike.core.ai.api.dto.AiBatchInfo;

import java.util.List;

/**
 * AI batch generation service (batch generation brought forward; document b: tp_ai_batch + tp_ai_task.batch_id).
 * Semantics: submit multiple test targets in one request and batch-output a test case list, with the single-submission limit set by the ai.batch.max-count configuration key and defaulting to 500, and require cost-estimate confirmation before generation.
 * The estimate is a pure calculation without database persistence, and progress x/N is aggregated with counts by done, failed, and pending status plus item details.
 * Partial failures retry only failed items by reusing single-task retry without regenerating successful items, and when all LLMs fail the tasks remain queued instead of being discarded.
 *
 */
public interface AiBatchService {

    /** Cost estimate is a pure calculation without database persistence, with count constrained by the configured upper limit, each non-empty item constrained to at most 5000, and invalid requests returning a human-readable 400 error. */
    AiBatchEstimate estimate(long projectId, List<String> requirements);

    /**
 * Cost estimate with the stage0 scenario multiplier ,
 * ruling section 10 item 1): when the batch has persisted scenario
 * previews, each line's token base is multiplied by that line's scenario
 * count and by the live deliberation round count (estimate = lines ×
 * scenarios × rounds, shown in real time in the confirmation dialog).
 * Without previews the legacy shape is kept untouched — the multiplier is
 * never fabricated from the configured cap.
 */
    AiBatchEstimate estimate(long projectId, List<String> requirements, Long previewBatchId);

    /** Create a batch generation by persisting tp_ai_batch to database and creating an AiTask for each item associated with batch_id, with MQ publishing performed by the Controller layer. */
    AiBatchCreateResult createBatch(AiBatchCreateInput input);

    /** Batch progress aggregation returns null when it does not exist, and isolation is guarded by the caller using projectId. */
    AiBatchInfo get(long batchId);

    /**
 * Latest batch of one project, any state : the newest
 * tp_ai_batch row ordered by id, aggregated through the same get(batchId)
 * read path so buckets never fork. Null when the project never submitted a
 * batch — the recovery door then honestly shows the empty panel.
 */
    AiBatchInfo findLatestByProject(long projectId);

 /** The current batch limit reads the live value of the ai.batch.max-count configuration key and falls back to 500 when the value is missing or out of range. */
    int currentMaxCount();

 /**
     * Whether the batch door should default to full scenario enumeration: when on,
     * a submit that never ran the stage0 preview enumerates the five scenario kinds
     * server-side and submits fully selected (one requirement point becomes several
     * cases). K8 key ai.gen.batch.auto-enumerate.enabled, shipped default on.
     */
    boolean currentAutoEnumerate();
}
