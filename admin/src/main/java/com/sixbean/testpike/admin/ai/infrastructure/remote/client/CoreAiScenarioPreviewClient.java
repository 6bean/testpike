package com.sixbean.testpike.admin.ai.infrastructure.remote.client;

import com.sixbean.testpike.admin.ai.api.dto.ScenarioPreviewRequest;
import com.sixbean.testpike.admin.ai.api.dto.ScenarioPreviewView;

import java.util.List;

/**
 * admin → core stage0 scenario preview ledger client :
 * the circle-selection UI runs one requirement line through the enumeration
 * preview and reads the durable rows back. Core owns the ledger and the
 * ai-service leg; this bridge only guards and forwards.
 */
public interface CoreAiScenarioPreviewClient {

    /**
     * Runs one line through the enumeration leg and stores the preview row.
     *
     * @return persisted row id, 0 when the K8 expansion switch was off
     */
    long create(ScenarioPreviewRequest request, long operatorId);

    /** All persisted previews of one batch in submission (seq) order. */
    List<ScenarioPreviewView> listByBatch(long batchId, long operatorId);
}
