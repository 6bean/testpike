package com.sixbean.testpike.core.perf.domain.component;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Engine × row-allocation support matrix .
 * <p>Both adapters already fail closed on the combinations their engine DSL
 * cannot honour; this class lifts that knowledge into one queryable source so
 * the wizard can disable unsupported strategies up front instead of letting
 * users save a scenario that only explodes at dispatch. If this matrix ever
 * drifts from the adapters' refusal points, these tests pin the contract.
 */
public class PerfDatasetAllocPolicyTest {

    @Test
    public void gatlingCarriesQueueAndCycleButNotPerUserSlices() {
        assertTrue(PerfDatasetAllocPolicy.supports("GATLING",
                PerfRowAllocModes.SEQUENTIAL_EXCLUSIVE));
        assertTrue(PerfDatasetAllocPolicy.supports("GATLING", PerfRowAllocModes.CYCLE));
        assertFalse(PerfDatasetAllocPolicy.supports("GATLING",
                PerfRowAllocModes.VUSER_EXCLUSIVE));
    }

    @Test
    public void k6CarriesPerUserSlicesAndCycleButNotQueueLeases() {
        assertTrue(PerfDatasetAllocPolicy.supports("K6",
                PerfRowAllocModes.VUSER_EXCLUSIVE));
        assertTrue(PerfDatasetAllocPolicy.supports("K6", PerfRowAllocModes.CYCLE));
        assertFalse(PerfDatasetAllocPolicy.supports("K6",
                PerfRowAllocModes.SEQUENTIAL_EXCLUSIVE));
    }

    @Test
    public void cycleIsTheCommonGroundAndNullModeIsNeverDeclaredSupported() {
        assertEquals(Set.of(PerfRowAllocModes.SEQUENTIAL_EXCLUSIVE, PerfRowAllocModes.CYCLE),
                PerfDatasetAllocPolicy.supportedModes("GATLING"));
        assertEquals(Set.of(PerfRowAllocModes.VUSER_EXCLUSIVE, PerfRowAllocModes.CYCLE),
                PerfDatasetAllocPolicy.supportedModes("K6"));
        // null = "follow the K8 default at dispatch" — the matrix only speaks
        // about explicit strategies, so a null query must not report support.
        assertFalse(PerfDatasetAllocPolicy.supports("GATLING", null));
    }

    @Test
    public void unknownEngineDeclaresNoStrategyInsteadOfGuessing() {
        assertFalse(PerfDatasetAllocPolicy.supports("JMETER", PerfRowAllocModes.CYCLE));
        assertTrue(PerfDatasetAllocPolicy.supportedModes("JMETER").isEmpty());
    }
}
