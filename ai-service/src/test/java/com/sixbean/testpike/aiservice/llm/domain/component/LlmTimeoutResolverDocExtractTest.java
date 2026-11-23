package com.sixbean.testpike.aiservice.llm.domain.component;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Extraction-plane timeout ceiling resolution .
 * <p>Pinned here: the K8 key ai.doc.llm-timeout-seconds wins over the global
 * llm.timeout-seconds for extraction-plane calls only; an unset/blank value
 * keeps the global face byte-identical (no domain override); garbage and
 * out-of-window values (the same 10~900 bounds as the global key) degrade to
 * the global face through the shared resolver path, never silently off.
 */
class LlmTimeoutResolverDocExtractTest {

    private static final String DOC_KEY = "ai.doc.llm-timeout-seconds";
    private static final String GLOBAL_KEY = "llm.timeout-seconds";

    private final CoreGateway coreGateway = mock(CoreGateway.class);
    private final LlmProperties properties = new LlmProperties();
    private final LlmTimeoutResolver resolver = new LlmTimeoutResolver(coreGateway, properties);

    /** Doc key set inside the window → the doc value is the effective ceiling. */
    @Test
    void docKeySet_usesDocValue() {
        when(coreGateway.getSysConfig(DOC_KEY)).thenReturn("45");
        when(coreGateway.getSysConfig(GLOBAL_KEY)).thenReturn("300");

        assertEquals(Duration.ofSeconds(45), resolver.resolveDocExtract());
    }

    /** Doc key unset → falls back to the global K8 face (zero drift). */
    @Test
    void docKeyMissing_fallsBackToGlobal() {
        when(coreGateway.getSysConfig(DOC_KEY)).thenReturn(null);
        when(coreGateway.getSysConfig(GLOBAL_KEY)).thenReturn("200");

        assertEquals(Duration.ofSeconds(200), resolver.resolveDocExtract());
    }

    /** Blank doc value = same unset semantics as the global key face. */
    @Test
    void docKeyBlank_fallsBackToGlobal() {
        when(coreGateway.getSysConfig(DOC_KEY)).thenReturn("  ");
        when(coreGateway.getSysConfig(GLOBAL_KEY)).thenReturn("200");

        assertEquals(Duration.ofSeconds(200), resolver.resolveDocExtract());
    }

    /** Out-of-window doc values (bounds shared with the global key) degrade
     *  to the global face instead of pinning an unusable ceiling. */
    @Test
    void docKeyOutOfRange_fallsBackToGlobal() {
        when(coreGateway.getSysConfig(GLOBAL_KEY)).thenReturn("200");
        when(coreGateway.getSysConfig(DOC_KEY)).thenReturn("9");
        assertEquals(Duration.ofSeconds(200), resolver.resolveDocExtract());

        when(coreGateway.getSysConfig(DOC_KEY)).thenReturn("901");
        assertEquals(Duration.ofSeconds(200), resolver.resolveDocExtract());
    }

    /** Garbage doc values degrade through the same non-silent warn path. */
    @Test
    void docKeyGarbage_fallsBackToGlobal() {
        when(coreGateway.getSysConfig(GLOBAL_KEY)).thenReturn("200");
        when(coreGateway.getSysConfig(DOC_KEY)).thenReturn("abc");

        assertEquals(Duration.ofSeconds(200), resolver.resolveDocExtract());
    }

    /** Core unreachable (null everywhere) → install default, same as resolve(). */
    @Test
    void docKeyUnreachable_fallsBackToInstallDefault() {
        when(coreGateway.getSysConfig(DOC_KEY)).thenReturn(null);
        when(coreGateway.getSysConfig(GLOBAL_KEY)).thenReturn(null);

        assertEquals(Duration.ofSeconds(LlmProperties.DEFAULT_TIMEOUT_SECONDS),
                resolver.resolveDocExtract());
    }
}
