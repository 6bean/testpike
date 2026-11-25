package com.sixbean.testpike.admin.mock.api.controller;

import com.sixbean.testpike.admin.ai.api.dto.AiTaskEventView;
import com.sixbean.testpike.admin.mock.infrastructure.remote.client.CoreMockRuleClient;
import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R62 bridge contract for the bulk-enrichment process-event tail door. The
 * admin bridge owns zero decisions: cursor window, row shape and refusal
 * wording are core's single source, so the bridge forwards them untouched
 * (batch/explore bridge-test posture). CoreMockRuleClient is the
 * integration-boundary double; the real admin-to-core chain is covered by
 * the feature's live E2E tail coverage.
 */
class MockJobProcessEventsBridgeTest {

    private static final long JOB_ID = 6601L;

    private final CoreMockRuleClient client = mock(CoreMockRuleClient.class);
    private final MockAiJobController controller = new MockAiJobController(client);

    @Test
    void anEmptyWindowIsForwardedAsAValidAnswerNotAnError() {
        when(client.aiJobProcessEvents(JOB_ID, 0L, null)).thenReturn(List.of());

        Response<List<AiTaskEventView>> answer = controller.processEvents(JOB_ID, 0L, null);

        assertTrue(answer.getData().isEmpty(),
                "while the run has not landed its first frame the drawer shows an empty tail");
        verify(client).aiJobProcessEvents(eq(JOB_ID), eq(0L), any());
    }

    @Test
    void rowsAndCursorParametersRideThroughUnchanged() {
        List<AiTaskEventView> tail = List.of(
                new AiTaskEventView(11L, "step", "enrichment started | 24 endpoints",
                        null, "2026-11-25T08:00:00"),
                new AiTaskEventView(12L, "error", "suggestion leg failed | model timeout",
                        "{\"code\":\"LLM_TIMEOUT\"}", "2026-11-25T08:04:00"));
        when(client.aiJobProcessEvents(JOB_ID, 7L, 200)).thenReturn(tail);

        Response<List<AiTaskEventView>> answer = controller.processEvents(JOB_ID, 7L, 200);

        assertEquals(tail, answer.getData(),
                "the bridge never rewrites cursor, level or detail on the way to the viewer");
        verify(client).aiJobProcessEvents(JOB_ID, 7L, 200);
    }

    @Test
    void aCoreRefusalPropagatesWithItsWordingIntact() {
        when(client.aiJobProcessEvents(anyLong(), anyLong(), any()))
                .thenThrow(new ServiceException("job not found", BizErrorCode.NOT_FOUND));

        ServiceException refusal = assertThrows(ServiceException.class,
                () -> controller.processEvents(9999L, 0L, null));
        assertEquals("job not found", refusal.getMessage(),
                "the honest not-found wording is core's single point, never reworded here");
    }
}
