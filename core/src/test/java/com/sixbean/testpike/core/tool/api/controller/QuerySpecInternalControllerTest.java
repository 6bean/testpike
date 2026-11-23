package com.sixbean.testpike.core.tool.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.tool.api.dto.QueryResult;
import com.sixbean.testpike.core.tool.api.dto.QuerySpecRunRequest;
import com.sixbean.testpike.core.tool.api.dto.QueryTargetView;
import com.sixbean.testpike.core.tool.domain.service.QuerySpecService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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
 * Internal aggregate-query door contract : the
 * dialog-side aggregate tool calls this door with the model-produced QuerySpec
 * JSON; the door is thin by rule — the interpreter owns every boundary, the
 * door only pins the transport contract and the fail-closed identity rule.
 * <p>Asserted face: ① the operator identity rides X-Operator-Id into the
 * service call (same passthrough posture as the tool invoke door); ② a missing
 * or non-positive operator id is refused BEFORE the interpreter runs (an
 * anonymous aggregate read is outside the five-way gate design — fail-closed,
 * zero query executed); ③ the body is handed to the interpreter as its raw
 * JSON text verbatim (re-read there, never re-shaped here); ④ the target-list
 * door exposes registry keys + allow-listed columns without leaking physical
 * table names (view shape pinned here).
 */
class QuerySpecInternalControllerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final QuerySpecService service = mock(QuerySpecService.class);
    private final QuerySpecInternalController controller =
            new QuerySpecInternalController(service);

    private static QueryResult sampleResult() {
        return new QueryResult("test_case", List.of("status", "cnt"),
                List.of(Map.of("status", 1, "cnt", 7L)), 200);
    }

    @Test
    void operatorHeaderAndProjectRideIntoTheInterpreter() throws Exception {
        when(service.runJson(any(), eq(3L), eq(7L))).thenReturn(sampleResult());

        var got = controller.run(3L, 7L,
                new QuerySpecRunRequest(MAPPER.readTree("{\"target\":\"test_case\"}")));

        assertEquals("test_case", got.getData().target());
        assertEquals(7L, got.getData().rows().get(0).get("cnt"));
        verify(service).runJson(eq("{\"target\":\"test_case\"}"), eq(3L), eq(7L));
    }

    @Test
    void missingOperatorHeaderIsRefusedBeforeAnyQueryRuns() throws Exception {
        ServiceException refused = assertThrows(ServiceException.class,
                () -> controller.run(3L, null,
                        new QuerySpecRunRequest(MAPPER.readTree("{\"target\":\"test_case\"}"))));
        assertTrue(refused.getMessage().contains("操作人"), refused.getMessage());
        verify(service, never()).runJson(any(), anyLong(), any());
    }

    @Test
    void zeroOperatorHeaderIsRefusedToo() throws Exception {
        // CoreGateway.call maps operatorId<=0 to a null header today, but the
        // door must not trust that: a literal 0 is anonymous too.
        assertThrows(ServiceException.class,
                () -> controller.run(3L, 0L,
                        new QuerySpecRunRequest(MAPPER.readTree("{\"target\":\"test_case\"}"))));
        verify(service, never()).runJson(any(), anyLong(), any());
    }

    @Test
    void interpreterRefusalPropagatesUntouched() throws Exception {
        when(service.runJson(any(), anyLong(), any()))
                .thenThrow(new ServiceException("只读查询目标「x」不在登记表内", 40001));
        ServiceException thrown = assertThrows(ServiceException.class,
                () -> controller.run(3L, 7L,
                        new QuerySpecRunRequest(MAPPER.readTree("{\"target\":\"x\"}"))));
        // The refusal sentence names the target — the dialog surfaces it to
        // the model verbatim, the door must not re-wrap it.
        assertTrue(thrown.getMessage().contains("不在登记表内"), thrown.getMessage());
    }

    @Test
    void targetListDoorCarriesRegistryColumnsNotPhysicalTables() {
        when(service.callableTargets()).thenReturn(List.of(
                new QueryTargetView("test_case", "status,priority", "review_fail_cnt", 200)));

        var views = controller.targets(7L).getData();

        assertEquals(1, views.size());
        assertEquals("test_case", views.get(0).targetKey());
        assertEquals("status,priority", views.get(0).dimensionColumns());
        assertEquals("review_fail_cnt", views.get(0).measureColumns());
        assertEquals(200, views.get(0).maxRows());
        // Compile-time pin: the view type has exactly four members — widening
        // toward base_table/scope_column/hard_filter is a leak, not a fix.
        assertEquals(4, QueryTargetView.class.getRecordComponents().length,
                "registry view must not widen toward physical columns");
    }

    @Test
    void targetListDoorAlsoRefusesAnonymousCallers() {
        assertThrows(ServiceException.class, () -> controller.targets(null));
        verify(service, never()).callableTargets();
    }
}
