package com.sixbean.testpike.aiservice.tool.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.ToolFcAssemblyRemoteItem;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.ToolInvokeResultRemote;
import com.sixbean.testpike.aiservice.llm.domain.LlmToolCall;
import com.sixbean.testpike.aiservice.tool.domain.FcPlan;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Session-anchor threading on the dispatcher seam :
 * the conversation segment {@code chatSessionId} joins the server-stamped trio
 * only on the new 8-arg overload — the dialog orchestrator carries it, the
 * async generation link keeps the old overload and therefore lands on the
 * stripping posture (model-forged anchors are erased before the payload
 * leaves ai-service, the doors refuse fail-closed afterwards).
 */
class ToolDispatcherDialogOcrTest {

    private static final Long ACTOR_ID = 9L;
    private static final long PROJECT_SLICE = 7L;
    private static final long SESSION_SLICE = 88L;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CoreGateway coreGateway = mock(CoreGateway.class);
    private final ToolDispatcher dispatcher = new ToolDispatcher(coreGateway, MAPPER);

    private static FcPlan planWith(ToolFcAssemblyRemoteItem... items) {
        Map<String, ToolFcAssemblyRemoteItem> index = new LinkedHashMap<>();
        for (ToolFcAssemblyRemoteItem i : items) {
            index.put(i.name(), i);
        }
        return new FcPlan(List.of(), Map.copyOf(index));
    }

    private static ToolFcAssemblyRemoteItem httpItem(long id, String name) {
        return new ToolFcAssemblyRemoteItem(id, name, 2, 1,
                "{\"type\":\"object\"}", 30000, false, null);
    }

    private void stubOk() {
        when(coreGateway.invokeTool(any(), any(), anyLong(), any(), any(), any()))
                .thenReturn(new ToolInvokeResultRemote(201L, "ocr", true, "{\"lines\":[]}",
                        null, null, null, 0L));
    }

    private String capturedInput() {
        ArgumentCaptor<String> input = ArgumentCaptor.forClass(String.class);
        verify(coreGateway).invokeTool(any(), any(), eq(201L), input.capture(), any(), any());
        return input.getValue();
    }

    /** The envelope must carry the server segment whenever the dispatch does . */
    private void verifyCarrierSlice(long slice) {
        verify(coreGateway).invokeTool(any(), any(), eq(201L), any(), any(), eq(slice));
    }

    @Test
    void dialogOverloadStampsTrioBeforeCore() throws Exception {
        stubOk();
        FcPlan plan = planWith(httpItem(201, "ocr.text_extract"));
        dispatcher.dispatch(plan, new LlmToolCall("c1", "ocr.text_extract",
                        "{\"objectKey\":\"k\"}"),
                ACTOR_ID, null, null, null, PROJECT_SLICE, SESSION_SLICE);
        String sent = capturedInput();
        verifyCarrierSlice(PROJECT_SLICE);
        assertEquals(SESSION_SLICE, MAPPER.readTree(sent).path("chatSessionId").asLong(), sent);
        assertEquals(PROJECT_SLICE, MAPPER.readTree(sent).path("projectId").asLong(), sent);
        assertEquals(9L, MAPPER.readTree(sent).path("operatorId").asLong(), sent);
    }

    @Test
    void legacyOverloadStripsForgedAnchorsOnOcrRows() throws Exception {
        stubOk();
        FcPlan plan = planWith(httpItem(201, "ocr.grid_read"));
        // Async generation link (no conversation): whatever the model claimed
        // must not reach the door — the trio leaves stripped, door refuses.
        dispatcher.dispatch(plan, new LlmToolCall("c1", "ocr.grid_read",
                        "{\"objectKey\":\"k\",\"chatSessionId\":1,\"projectId\":2,\"operatorId\":3}"),
                null, null, null, null, null);
        String sent = capturedInput();
        // No segment on the dispatch = no anchor on the envelope either; core then
        // refuses any surviving project claim fail-closed (smuggling zero-relax).
        verify(coreGateway).invokeTool(any(), any(), eq(201L), any(), any(), isNull());
        assertFalse(sent.contains("chatSessionId"), "forged session must not ride on: " + sent);
        assertFalse(sent.contains("operatorId"), "forged operator must not ride on: " + sent);
        assertEquals("k", MAPPER.readTree(sent).path("objectKey").asText());
    }

    @Test
    void otherHttpRowsRideByteIdentical() {
        stubOk();
        String args = "{\"endpointKey\":\"GET /internal/tags\",\"arguments\":{}}";
        FcPlan plan = planWith(httpItem(201, "some.other.http.row"));
        dispatcher.dispatch(plan, new LlmToolCall("c1", "some.other.http.row", args),
                ACTOR_ID, null, null, null, PROJECT_SLICE, SESSION_SLICE);
        assertEquals(args, capturedInput());
    }
}
