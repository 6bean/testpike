package com.sixbean.testpike.core.webelement.api.controller;

import com.sixbean.testpike.core.webelement.api.dto.RevisionStormView;
import com.sixbean.testpike.core.webelement.api.dto.WebElementHealthView;
import com.sixbean.testpike.core.webelement.api.dto.WebElementView;
import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.webelement.domain.service.WebElementHealthService;
import com.sixbean.testpike.core.webelement.domain.service.WebElementService;
import com.sixbean.testpike.core.webelement.domain.service.WebElementStormService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Health/storm read doors contract (WE S7 board feed): the doors travel the
 * single ledger segment door's rows, ask the health service once for exactly
 * those ids, and answer the honest unreached reading for rows the rollup
 * never saw — an element with no outcome must not silently disappear from
 * the ledger column. Storms pass straight through, two signals side by side.
 * <p>Service doubles isolate the wiring contract only; the rollup SQL and the
 * storm aggregation semantics live behind their own tests (health daily
 * account + storm service suites), and the admin bridge test pins the
 * transport one layer up.
 */
class WebElementHealthStormInternalControllerTest {

    private final WebElementService webElementService = mock(WebElementService.class);
    private final WebElementHealthService healthService = mock(WebElementHealthService.class);
    private final WebElementStormService stormService = mock(WebElementStormService.class);
    private final WebElementHealthStormInternalController controller =
            new WebElementHealthStormInternalController(webElementService, healthService, stormService);

    @Test
    void health_answersEverySliceRow_andKeepsUnreachedRowsHonest() {
        when(webElementService.listByProject(eq(7L), any()))
                .thenReturn(List.of(row(21L), row(22L)));
        when(healthService.healthFor(List.of(21L, 22L))).thenReturn(Map.of(
                21L, new WebElementHealthView(21L, true, 40,
                        WebElementHealthView.BADGE_YELLOW, true)));

        Response<List<WebElementHealthView>> resp = controller.health(7L, null);

        assertEquals("0", resp.getCode());
        List<WebElementHealthView> readings = resp.getData();
        assertEquals(2, readings.size());
        WebElementHealthView first = readings.get(0);
        assertEquals(21L, first.elementId());
        assertEquals(WebElementHealthView.BADGE_YELLOW, first.badge());
        assertEquals(true, first.aboutToBreak());
        WebElementHealthView second = readings.get(1);
        assertEquals(22L, second.elementId());
        assertEquals(WebElementHealthView.BADGE_NONE, second.badge());
        assertEquals(false, second.reached());
        assertEquals(-1, second.missRatePercent());
    }

    @Test
    void storms_passThroughTheSameAggregationTheEarlyWarningUses() {
        RevisionStormView storm = new RevisionStormView("https://shop.example.com/login",
                List.of(21L, 22L), List.of(23L));
        when(stormService.activeStorms(7L)).thenReturn(List.of(storm));

        List<RevisionStormView> active = controller.storms(7L).getData();

        assertEquals(1, active.size());
        assertEquals(List.of(21L, 22L), active.get(0).staleElementIds());
        assertEquals(List.of(23L), active.get(0).aboutToBreakElementIds());
    }

    private static WebElementView row(long id) {
        return new WebElementView(id, 7L, "按钮" + id, "https://shop.example.com/login",
                List.of(), List.of(), 1, 0.8, 0L, 0L, 3, null, null);
    }
}
