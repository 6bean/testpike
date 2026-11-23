package com.sixbean.testpike.core.ai.domain.service;

import com.sixbean.testpike.core.ai.api.dto.ScenarioPreviewInfo;
import com.sixbean.testpike.core.ai.api.dto.ScenarioPreviewInput;

import java.util.List;

/**
 * Stage0 scenario preview ledger : persists the enumeration
 * outcome of each requirement line so the human circle-selection step reads a
 * durable draft, and exposes per-batch scenario counts for the cost estimate
 * multiplier. A switch-off enumeration writes nothing (legacy shape).
 */
public interface ScenarioPreviewService {

    /**
     * Runs one line through the ai-service enumeration leg and stores the
     * surviving scenario list.
     *
     * @return persisted preview row id, or 0 when the K8 switch was off
     */
    long create(ScenarioPreviewInput input);

    /** All persisted previews of one batch, in submission (seq) order. */
    List<ScenarioPreviewInfo> listByBatch(long batchId);

    /**
     * Per-row scenario counts of one batch in submission order — the cost
     * estimate multiplier source (rows with an empty list report 0 honestly).
     */
    List<Integer> scenarioCountsByBatch(long batchId);
}
