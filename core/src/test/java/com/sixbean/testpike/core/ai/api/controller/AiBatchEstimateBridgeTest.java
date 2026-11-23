package com.sixbean.testpike.core.ai.api.controller;

import com.sixbean.testpike.common.aibatch.AiBatchItem;
import com.sixbean.testpike.core.ai.api.dto.AiBatchCreateRequest;
import com.sixbean.testpike.core.ai.api.dto.AiBatchEstimate;
import com.sixbean.testpike.core.ai.domain.service.AiBatchService;
import com.sixbean.testpike.core.events.domain.service.AiGenPublisher;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Estimate-bridge contract test of the batch internal door :
 * the preview batch id carried on the request must reach the three-argument
 * estimate overload of the service, so the cost figure shown in the generation
 * confirmation dialog uses the persisted stage0 scenario multiplier instead of
 * the legacy per-line shape. A request without a preview batch keeps the null
 * passthrough (multiplier never fabricated).
 * <p>The service is the isolated seam here (same posture as the sibling bridge
 * lane); the live admin→core→ai-service chain rides the L3 window.
 */
class AiBatchEstimateBridgeTest {

    private final AiBatchService aiBatchService = mock(AiBatchService.class);
    private final AiGenPublisher aiGenPublisher = mock(AiGenPublisher.class);
    private final AiBatchInternalController controller =
            new AiBatchInternalController(aiBatchService, aiGenPublisher);

    @Test
    void estimatePassesPreviewBatchIdToThreeArgOverload() {
        AiBatchEstimate estimate = new AiBatchEstimate(2, 1200, 0.02, 500, 6, 2);
        when(aiBatchService.estimate(eq(7L), anyList(), eq(88L))).thenReturn(estimate);

        AiBatchCreateRequest request = new AiBatchCreateRequest(7L,
                List.of("用户登录功能", "订单支付功能"), null, 88L);
        controller.estimate(request);

        verify(aiBatchService).estimate(eq(7L), eq(List.of("用户登录功能", "订单支付功能")), eq(88L));
    }

    @Test
    void estimateWithoutPreviewBatchPassesNullNotNullId() {
        when(aiBatchService.estimate(eq(7L), anyList(), isNull())).thenReturn(
                new AiBatchEstimate(1, 300, 0.01, 500, null, null));

        AiBatchCreateRequest request = new AiBatchCreateRequest(7L,
                List.of("库存查询功能"), List.of(new AiBatchItem("库存查询功能", null, null)));
        controller.estimate(request);

        verify(aiBatchService).estimate(eq(7L), eq(List.of("库存查询功能")), isNull());
    }
}
