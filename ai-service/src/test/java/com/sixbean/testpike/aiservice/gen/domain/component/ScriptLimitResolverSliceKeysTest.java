package com.sixbean.testpike.aiservice.gen.domain.component;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * L2 for the runtime read surface of the three segment keys :
 * every access is a fresh hot read (no cache), and missing rows, dirty text or
 * out-of-range numbers fall back to the contract defaults so a broken config
 * row can never stall an in-flight generation task.
 */
class ScriptLimitResolverSliceKeysTest {

    private final CoreGateway coreGateway = mock(CoreGateway.class);
    private final ScriptLimitResolver resolver = new ScriptLimitResolver(coreGateway);

    @Test
    void missingRowsFallBackToContractDefaults() {
        when(coreGateway.getSysConfig(ScriptLimitResolver.KEY_SLICE_STEPS)).thenReturn(null);
        when(coreGateway.getSysConfig(ScriptLimitResolver.KEY_SLICE_TIMEOUT_SECONDS)).thenReturn(null);
        when(coreGateway.getSysConfig(ScriptLimitResolver.KEY_SLICE_RETRY)).thenReturn(null);

        assertEquals(10, resolver.sliceSteps());
        assertEquals(900, resolver.sliceTimeoutSeconds());
        assertEquals(1, resolver.sliceRetry());
    }

    @Test
    void dirtyValuesFallBackToDefaults() {
        when(coreGateway.getSysConfig(ScriptLimitResolver.KEY_SLICE_STEPS)).thenReturn("abc");
        when(coreGateway.getSysConfig(ScriptLimitResolver.KEY_SLICE_TIMEOUT_SECONDS)).thenReturn("");

        assertEquals(10, resolver.sliceSteps());
        assertEquals(900, resolver.sliceTimeoutSeconds());
    }

    @Test
    void outOfRangeValuesFallBackToDefaults() {
        when(coreGateway.getSysConfig(ScriptLimitResolver.KEY_SLICE_STEPS)).thenReturn("101");
        assertEquals(10, resolver.sliceSteps(), "width above 100 must fall back");

        when(coreGateway.getSysConfig(ScriptLimitResolver.KEY_SLICE_STEPS)).thenReturn("-1");
        assertEquals(10, resolver.sliceSteps(), "negative width must fall back");

        when(coreGateway.getSysConfig(ScriptLimitResolver.KEY_SLICE_TIMEOUT_SECONDS)).thenReturn("799");
        assertEquals(900, resolver.sliceTimeoutSeconds(), "below the per-hop floor must fall back");

        when(coreGateway.getSysConfig(ScriptLimitResolver.KEY_SLICE_TIMEOUT_SECONDS)).thenReturn("3601");
        assertEquals(900, resolver.sliceTimeoutSeconds(), "above 3600 must fall back");

        when(coreGateway.getSysConfig(ScriptLimitResolver.KEY_SLICE_RETRY)).thenReturn("4");
        assertEquals(1, resolver.sliceRetry(), "retry above 3 must fall back");
    }

    @Test
    void inRangeValuesIncludingOffWidthAreHonoured() {
        when(coreGateway.getSysConfig(ScriptLimitResolver.KEY_SLICE_STEPS)).thenReturn("5");
        assertEquals(5, resolver.sliceSteps());

        when(coreGateway.getSysConfig(ScriptLimitResolver.KEY_SLICE_STEPS)).thenReturn("0");
        assertEquals(0, resolver.sliceSteps(), "width 0 is legal = slicing off");

        when(coreGateway.getSysConfig(ScriptLimitResolver.KEY_SLICE_TIMEOUT_SECONDS)).thenReturn("1200");
        assertEquals(1200, resolver.sliceTimeoutSeconds());

        when(coreGateway.getSysConfig(ScriptLimitResolver.KEY_SLICE_RETRY)).thenReturn("0");
        assertEquals(0, resolver.sliceRetry(), "0 = no retry");
    }

    @Test
    void stageTwoConstraintSegmentStaysFreeOfSliceKeys() {
        String segment = resolver.promptConstraintSegment();

        assertFalse(segment.contains("拆片"),
                "slice keys are scheduling knobs and must not enter the prompt (byte drift): " + segment);
        assertTrue(segment.contains("步骤 ≤50"),
                "the legacy nine-key constraint line must stay intact: " + segment);
    }
}
