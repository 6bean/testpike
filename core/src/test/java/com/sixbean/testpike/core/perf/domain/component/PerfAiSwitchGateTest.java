package com.sixbean.testpike.core.perf.domain.component;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Switch matrix for the two AI load-test drafting points (scenario draft and
 * run-insight read-out). Each point is guarded by its own K8 key, both default
 * off; a closed gate must refuse with a plain word naming exactly the key an
 * operator has to flip, and an unknown point name must refuse instead of
 * silently passing.
 */
class PerfAiSwitchGateTest {

    /** ConfigService stub backed by an in-memory row map (read path only — the
     *  switch gate never touches the CRUD surface). */
    private static ConfigService mapConfig(Map<String, String> rows) {
        ConfigService configService = Mockito.mock(ConfigService.class);
        Mockito.when(configService.getValue(Mockito.anyString(), Mockito.any()))
                .thenAnswer(invocation -> {
                    String key = invocation.getArgument(0);
                    String fallback = invocation.getArgument(1);
                    return rows.getOrDefault(key, fallback);
                });
        return configService;
    }

    @Test
    void closedSwitchRefusesNamingTheExactKeyToOpen() {
        PerfAiSwitchGate gate = new PerfAiSwitchGate(mapConfig(new HashMap<>()));

        ServiceException e = assertThrows(ServiceException.class,
                () -> gate.requireOpen(PerfAiSwitchGate.POINT_SCENARIO_DRAFT));
        assertEquals(BizErrorCode.FORBIDDEN, e.getCode());
        assertTrue(e.getMessage().contains("ai.perf.scenario-draft"),
                "refusal must name the key an operator flips: " + e.getMessage());

        ServiceException insightClosed = assertThrows(ServiceException.class,
                () -> gate.requireOpen(PerfAiSwitchGate.POINT_INSIGHT));
        assertTrue(insightClosed.getMessage().contains("ai.perf.insight"),
                "insight point must name its own key: " + insightClosed.getMessage());
    }

    @Test
    void explicitTrueRowOpensThePoint() {
        Map<String, String> rows = new HashMap<>();
        rows.put("ai.perf.scenario-draft", "true");
        rows.put("ai.perf.insight", "TRUE");
        PerfAiSwitchGate gate = new PerfAiSwitchGate(mapConfig(rows));

        gate.requireOpen(PerfAiSwitchGate.POINT_SCENARIO_DRAFT);
        gate.requireOpen(PerfAiSwitchGate.POINT_INSIGHT);
    }

    @Test
    void dirtyRowValueKeepsThePointClosed() {
        // Only an explicit true opens; anything else (typos, 1-as-text, blank)
        // stays closed so a mistyped row can never switch an AI point on.
        Map<String, String> rows = new HashMap<>();
        rows.put("ai.perf.scenario-draft", "yes");
        rows.put("ai.perf.insight", " ");
        PerfAiSwitchGate gate = new PerfAiSwitchGate(mapConfig(rows));

        assertThrows(ServiceException.class,
                () -> gate.requireOpen(PerfAiSwitchGate.POINT_SCENARIO_DRAFT));
        assertThrows(ServiceException.class,
                () -> gate.requireOpen(PerfAiSwitchGate.POINT_INSIGHT));
    }

    @Test
    void unknownPointNameRefusesInsteadOfPassing() {
        PerfAiSwitchGate gate = new PerfAiSwitchGate(mapConfig(new HashMap<>()));

        ServiceException e = assertThrows(ServiceException.class,
                () -> gate.requireOpen("telepathy"));
        assertTrue(e.getMessage().contains("telepathy"),
                "refusal must name the point the caller asked for: " + e.getMessage());
    }
}
