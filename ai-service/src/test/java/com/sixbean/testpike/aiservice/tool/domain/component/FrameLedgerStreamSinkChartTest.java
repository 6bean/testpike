package com.sixbean.testpike.aiservice.tool.domain.component;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Chart embedding on the trace frame : the live
 * card renders from meta.chart on the tool_done trace frame — the same body
 * the model saw, journaled once (replay/reconnect reuse the ledger with zero
 * new frame types, guide-embedding posture from ).
 * <p>Existing guide behaviour is re-pinned here so the extraction cannot
 * regress into a chart-only special case: non-aggregate tools journal under
 * guide, aggregate.query journals under chart, refusals journal neither.
 */
class FrameLedgerStreamSinkChartTest {

    private static final String AGG_BODY =
            "{\"target\":\"test_case\",\"columns\":[\"status\",\"cnt\"],"
                    + "\"rows\":[{\"status\":1,\"cnt\":7}],\"rowLimitUsed\":200}";

    private String metaOf(String toolName, boolean success, String output) {
        CoreGateway gateway = mock(CoreGateway.class);
        FrameLedgerStreamSink sink = new FrameLedgerStreamSink(gateway, 9L, 42L);
        sink.onToolDone(toolName, success, output);
        ArgumentCaptor<String> meta = ArgumentCaptor.forClass(String.class);
        verify(gateway).appendChatFrame(eq(9L), eq(42L), eq("trace"), any(), meta.capture());
        return meta.getValue();
    }

    @Test
    void aggregateSuccessJournalsChartPayloadUnderMetaChart() {
        String meta = metaOf(AggregateQueryTool.NAME, true, AGG_BODY);

        assertTrue(meta.contains("\"chart\":"), meta);
        assertTrue(meta.contains("\"target\":\"test_case\""), meta);
        assertTrue(meta.contains("\"rowLimitUsed\":200"), meta);
        // The frontend renders columns/rows directly — no reshaping allowed.
        assertTrue(meta.contains("\"columns\":[\"status\",\"cnt\"]"), meta);
        assertFalse(meta.contains("\"guide\":"),
                "the aggregate body is not a guide payload: " + meta);
    }

    @Test
    void aggregateRefusalOrBrokenBodyJournalsNoChart() {
        assertFalse(metaOf(AggregateQueryTool.NAME, false, "目标不在登记表内")
                .contains("\"chart\":"), "a refusal must not fake a card");
        assertFalse(metaOf(AggregateQueryTool.NAME, true, "not-json{{")
                .contains("\"chart\":"), "an unparseable body must not break the jsonb column");
    }

    @Test
    void flowGuideStillJournalsUnderGuideNotChart() {
        String meta = metaOf(FrameLedgerStreamSink.TOOL_CASE_FLOW_GUIDE, true,
                "{\"caseId\":5,\"states\":[]}");
        assertTrue(meta.contains("\"guide\":"), meta);
        assertFalse(meta.contains("\"chart\":"), meta);
    }
}
