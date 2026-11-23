package com.sixbean.testpike.aiservice.gen.domain.component;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway.ApiEndpointBrief;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway.ApiParamBrief;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway.ApiSpecBrief;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway.MockServingBrief;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway.PageRows;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmEndpointConfig;
import com.sixbean.testpike.aiservice.llm.domain.component.TokenCounter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * unit tests of the contract truth-source injection bridge.
 * <p>Assertion surface (five verdicts, V428 head-note contract):
 * 1) gate off = empty segment with zero remote reads;
 * 2) project carries no contract rows = empty segment and the measured-sample
 * read is never issued;
 * 3) a measured mock row joined on method+path contributes its field names and
 * the response sample line to the segment;
 * 4) any gateway exception degrades to an empty segment (fail-soft, the
 * generation chain is never interrupted);
 * 5) over-budget endpoint blocks are dropped whole at the tail — a partial
 * line must never appear (DdlDictBridge same discipline).
 * <p>L2 posture follows DdlDictBridgeTest: mock CoreGateway + live
 * {@link TokenCounter}; real-chain evidence belongs to the L3/L4 window.
 */
class ContractTruthSourceBridgeTest {

    private static final long PROJECT_ID = 7L;
    private static final String GATE_KEY = "ai.gen.truth-source.api-spec-schema";
    private static final String BUDGET_KEY = "ai.doc.token-budget";

    private final CoreGateway coreGateway = mock(CoreGateway.class);

    private ContractTruthSourceBridge bridge() {
        return new ContractTruthSourceBridge(coreGateway, new TokenCounter());
    }

    private static LlmEndpointConfig endpoint() {
        return new LlmEndpointConfig("openai", "m", "k", "http://localhost:4000/v1",
                LlmEndpointConfig.SOURCE_CONFIG);
    }

    private static ApiSpecBrief spec(String name, List<ApiEndpointBrief> endpoints) {
        return new ApiSpecBrief(1L, name, 1, endpoints);
    }

    private static ApiEndpointBrief getOrders(Map<String, String> responseSchemas) {
        return new ApiEndpointBrief("GET", "/orders",
                List.of(new ApiParamBrief("orderId")), responseSchemas);
    }

    @Test
    void gateOffReturnsEmptySegmentWithZeroRemoteReads() {
        when(coreGateway.getSysConfig(GATE_KEY)).thenReturn("0");

        String segment = bridge().contractSegment(PROJECT_ID, endpoint());

        assertEquals("", segment, "gate off must inject nothing (factory default is on, "
                + "an explicit 0 turns the whole read path off)");
        verify(coreGateway, never()).listApiSpecs(anyLong(), anyInt(), anyInt());
        verify(coreGateway, never()).listMockServingInventory(anyLong(), anyInt(), anyInt());
    }

    @Test
    void emptyContractStoreSkipsTheMeasuredReadAndInjectsNothing() {
        when(coreGateway.getSysConfig(GATE_KEY)).thenReturn("true");
        when(coreGateway.listApiSpecs(anyLong(), anyInt(), anyInt()))
                .thenReturn(new PageRows<>(List.of(), 0));

        String segment = bridge().contractSegment(PROJECT_ID, endpoint());

        assertEquals("", segment, "projects without contracts keep the legacy prompt byte-for-byte");
        verify(coreGateway, never()).listMockServingInventory(anyLong(), anyInt(), anyInt());
    }

    @Test
    void measuredHitJoinsFieldNamesAndSampleIntoTheSegment() {
        when(coreGateway.getSysConfig(GATE_KEY)).thenReturn("1");
        when(coreGateway.listApiSpecs(anyLong(), anyInt(), anyInt()))
                .thenReturn(new PageRows<>(List.of(spec("order-api", List.of(getOrders(
                        Map.of("200", "{\"properties\":{\"orderId\":{\"type\":\"string\"},"
                                + "\"totalAmount\":{\"type\":\"number\"}}}"))))), 1));
        when(coreGateway.listMockServingInventory(anyLong(), anyInt(), anyInt()))
                .thenReturn(new PageRows<>(List.of(new MockServingBrief(9L, "get", "/orders",
                        "{\"orderId\":\"A-100\",\"totalAmount\":25.5}", 7L)), 1));

        String segment = bridge().contractSegment(PROJECT_ID, endpoint());

        assertTrue(segment.contains("/orders"), "endpoint path must appear: " + segment);
        assertTrue(segment.contains("orderId"), "contract field names must appear: " + segment);
        assertTrue(segment.contains("totalAmount"), "response schema fields must appear: "
                + segment);
        assertTrue(segment.contains("A-100"), "measured sample payload must appear: " + segment);
    }

    @Test
    void gatewayFailureDegradesToEmptySegment() {
        when(coreGateway.getSysConfig(GATE_KEY)).thenReturn("1");
        when(coreGateway.listApiSpecs(anyLong(), anyInt(), anyInt()))
                .thenThrow(new IllegalStateException("core unreachable"));

        String segment = bridge().contractSegment(PROJECT_ID, endpoint());

        assertEquals("", segment, "any read failure degrades to nothing (V428 head-note); "
                + "the generation chain must never break on this bridge");
    }

    @Test
    void overBudgetTailBlockIsDroppedWholeNotHalf() {
        when(coreGateway.getSysConfig(GATE_KEY)).thenReturn("1");
        when(coreGateway.getSysConfig(BUDGET_KEY)).thenReturn("400");
        StringBuilder huge = new StringBuilder("{\"properties\":{");
        for (int i = 0; i < 300; i++) {
            huge.append("\"inventoryForecastField").append(i)
                    .append("\":{\"type\":\"string\"},");
        }
        huge.setLength(huge.length() - 1);
        String hugeSchema = huge.append("}}").toString();
        ApiEndpointBrief longBlock = new ApiEndpointBrief("POST", "/inventory/forecast",
                List.of(new ApiParamBrief("warehouseCode")),
                Map.of("200", hugeSchema));
        when(coreGateway.listApiSpecs(anyLong(), anyInt(), anyInt()))
                .thenReturn(new PageRows<>(List.of(spec("mixed-api",
                        List.of(getOrders(Map.of("200",
                                "{\"properties\":{\"orderId\":{\"type\":\"string\"}}}")),
                                longBlock))), 2));
        when(coreGateway.listMockServingInventory(anyLong(), anyInt(), anyInt()))
                .thenReturn(new PageRows<>(List.of(), 0));

        String segment = bridge().contractSegment(PROJECT_ID, endpoint());

        assertTrue(segment.contains("/orders"), "the block that still fits must be kept: "
                + segment);
        assertFalse(segment.contains("/inventory/forecast"),
                "the over-budget tail block must be dropped whole: " + segment);
        assertFalse(segment.contains("warehouseCode"),
                "a dropped block must leave no half line behind: " + segment);
    }
}
