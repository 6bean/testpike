package com.sixbean.testpike.aiservice.llm.domain.component;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.LlmConfigBrief;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Reprobe scheduler contract (ticket-② : the dormant prober gets its
 * production caller here. The round must (a) take rows that fell out of the
 * interval window from the ledger, (b) resolve each model name against the
 * live enabled-config catalog and probe with that row only — never a stub
 * endpoint (fail-closed, the same posture the prober javadoc demands),
 * (c) honour the K8 interval and daily-call budget read per tick (hot
 * update, no restart), and (d) stay silent — zero internal calls — when
 * nothing is due.
 */
class LlmCapabilityReprobeSchedulerTest {

    private final LlmOutputCapabilityLedger ledger = mock(LlmOutputCapabilityLedger.class);
    private final LlmCapabilityProber prober = mock(LlmCapabilityProber.class);
    private final CoreGateway gateway = mock(CoreGateway.class);
    private final com.sixbean.testpike.aiservice.sched.domain.component.JobRunner jobRunner =
            mock(com.sixbean.testpike.aiservice.sched.domain.component.JobRunner.class);
    private final LlmCapabilityReprobeScheduler scheduler =
            new LlmCapabilityReprobeScheduler(ledger, prober, gateway, jobRunner);

    private static LlmConfigBrief catalogRow(String model) {
        return new LlmConfigBrief(1L, model, "openai", model, "key",
                "http://base", "catalog");
    }

    private void stubCatalog(LlmConfigBrief... rows) {
        when(gateway.listEnabledLlmConfigs()).thenReturn(List.of(rows));
    }

    @Test
    void dueRowsAreProbedAgainstTheirLiveCatalogRow() {
        when(gateway.getSysConfig(anyString())).thenReturn(null);
        when(ledger.findStaleObservations(anyInt())).thenReturn(List.<String[]>of(
                new String[]{"model-a", LlmOutputCapabilityLedger.FORM_TOOLS_SCHEMA}));
        stubCatalog(catalogRow("model-a"));

        int probes = scheduler.reprobeOnce();

        assertEquals(1, probes);
        ArgumentCaptor<LlmEndpointConfig> used =
                ArgumentCaptor.forClass(LlmEndpointConfig.class);
        verify(prober).probeAndRecord(used.capture(),
                eq(LlmOutputCapabilityLedger.FORM_TOOLS_SCHEMA));
        assertEquals("model-a", used.getValue().modelName());
        assertEquals("http://base", used.getValue().baseUrl());
    }

    @Test
    void dailyBudgetCapsProbesPerDayAndRefusesTheRest() {
        when(gateway.getSysConfig(LlmCapabilityReprobeScheduler.KEY_DAILY_MAX))
                .thenReturn("1");
        when(gateway.getSysConfig(LlmCapabilityReprobeScheduler.KEY_INTERVAL_HOURS))
                .thenReturn("1");
        when(ledger.findStaleObservations(anyInt())).thenReturn(List.<String[]>of(
                new String[]{"model-a", LlmOutputCapabilityLedger.FORM_TOOLS_SCHEMA},
                new String[]{"model-b", LlmOutputCapabilityLedger.FORM_TOOLS_SCHEMA}));
        stubCatalog(catalogRow("model-a"), catalogRow("model-b"));

        int first = scheduler.reprobeOnce();
        int second = scheduler.reprobeOnce();

        assertEquals(1, first);
        assertEquals(0, second);
        verify(prober, times(1)).probeAndRecord(any(), anyString());
    }

    @Test
    void nothingDueMeansNoCatalogCall() {
        when(gateway.getSysConfig(anyString())).thenReturn(null);
        when(ledger.findStaleObservations(anyInt())).thenReturn(List.<String[]>of());

        int probes = scheduler.reprobeOnce();

        assertEquals(0, probes);
        verify(gateway, never()).listEnabledLlmConfigs();
        verify(prober, never()).probeAndRecord(any(), anyString());
    }

    @Test
    void modelsWithoutALiveCatalogRowAreSkippedNotStubbed() {
        when(gateway.getSysConfig(anyString())).thenReturn(null);
        when(ledger.findStaleObservations(anyInt())).thenReturn(List.<String[]>of(
                new String[]{"ghost-model", LlmOutputCapabilityLedger.FORM_SCHEMA_ONLY}));
        stubCatalog(catalogRow("other-model"));

        int probes = scheduler.reprobeOnce();

        assertEquals(0, probes);
        verify(prober, never()).probeAndRecord(any(), anyString());
    }

    @Test
    void emptyCatalogFailsClosedWithoutTouchingTheProber() {
        when(gateway.getSysConfig(anyString())).thenReturn(null);
        when(ledger.findStaleObservations(anyInt())).thenReturn(List.<String[]>of(
                new String[]{"model-a", LlmOutputCapabilityLedger.FORM_TOOLS_SCHEMA}));
        when(gateway.listEnabledLlmConfigs()).thenReturn(List.of());

        int probes = scheduler.reprobeOnce();

        assertEquals(0, probes);
        verify(prober, never()).probeAndRecord(any(), anyString());
    }

    @Test
    void illegalK8IntervalFallsBackToFactoryDefault() {
        when(gateway.getSysConfig(anyString())).thenReturn(null);
        when(gateway.getSysConfig(LlmCapabilityReprobeScheduler.KEY_INTERVAL_HOURS))
                .thenReturn("0");
        when(ledger.findStaleObservations(eq(LlmCapabilityReprobeScheduler.DEFAULT_INTERVAL_HOURS)))
                .thenReturn(List.<String[]>of());

        int probes = scheduler.reprobeOnce();

        assertEquals(0, probes);
        verify(ledger).findStaleObservations(LlmCapabilityReprobeScheduler.DEFAULT_INTERVAL_HOURS);
    }

    @Test
    void outOfIntervalDailyBudgetFallsBackToFactoryDefault() {
        when(gateway.getSysConfig(LlmCapabilityReprobeScheduler.KEY_DAILY_MAX))
                .thenReturn("99999");
        when(gateway.getSysConfig(anyString())).thenReturn(null);
        when(ledger.findStaleObservations(anyInt())).thenReturn(List.<String[]>of(
                new String[]{"model-a", LlmOutputCapabilityLedger.FORM_TOOLS_SCHEMA}));
        stubCatalog(catalogRow("model-a"));
        when(prober.probeAndRecord(any(), anyString())).thenReturn(LlmProbeVerdict.UNKNOWN);

        int probes = scheduler.reprobeOnce();

        // Illegal cap degrades to 20, not to unlimited: the one due row still probes.
        assertEquals(1, probes);
        verify(prober).probeAndRecord(any(),
                eq(LlmOutputCapabilityLedger.FORM_TOOLS_SCHEMA));
    }
}
