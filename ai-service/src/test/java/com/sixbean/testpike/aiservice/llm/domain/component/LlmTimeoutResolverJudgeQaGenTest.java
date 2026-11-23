package com.sixbean.testpike.aiservice.llm.domain.component;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Question-generation plane provider-call timeout ceiling .
 * <p>Pinned here: the K8 key ai.judge.qa-gen.llm-timeout-seconds wins over the
 * global llm.timeout-seconds for qa-gen group calls only; unset/blank keeps the
 * global face byte-identical (opt-in tightening, zero drift); garbage and
 * out-of-window values (same 10~900 bounds as the global key) degrade to the
 * global face through the shared resolver path, never silently off. The shared
 * clamp-composition seam (clampUnderCeiling) is asserted here as the single
 * source both planes consume — shortens only, never lengthens.
 */
class LlmTimeoutResolverJudgeQaGenTest {

    private static final String JUDGE_KEY = "ai.judge.qa-gen.llm-timeout-seconds";
    private static final String GLOBAL_KEY = "llm.timeout-seconds";

    private final CoreGateway coreGateway = mock(CoreGateway.class);
    private final LlmProperties properties = new LlmProperties();
    private final LlmTimeoutResolver resolver = new LlmTimeoutResolver(coreGateway, properties);

    /** Judge key set inside the window → the judge value is the effective ceiling. */
    @Test
    void judgeKeySet_usesJudgeValue() {
        when(coreGateway.getSysConfig(JUDGE_KEY)).thenReturn("120");
        when(coreGateway.getSysConfig(GLOBAL_KEY)).thenReturn("300");

        assertEquals(Duration.ofSeconds(120), resolver.resolveJudgeQaGen());
    }

    /** Judge key unset → falls back to the global K8 face (zero drift). */
    @Test
    void judgeKeyMissing_fallsBackToGlobal() {
        when(coreGateway.getSysConfig(JUDGE_KEY)).thenReturn(null);
        when(coreGateway.getSysConfig(GLOBAL_KEY)).thenReturn("200");

        assertEquals(Duration.ofSeconds(200), resolver.resolveJudgeQaGen());
    }

    /** Blank judge value = same unset semantics as the doc-plane face. */
    @Test
    void judgeKeyBlank_fallsBackToGlobal() {
        when(coreGateway.getSysConfig(JUDGE_KEY)).thenReturn("  ");
        when(coreGateway.getSysConfig(GLOBAL_KEY)).thenReturn("200");

        assertEquals(Duration.ofSeconds(200), resolver.resolveJudgeQaGen());
    }


    /** 600 sits inside the widened window and wins over the global face
     *  (factory default after the 2026-11-09 ceiling raise). */
    @Test
    void sixHundredWithinWidenedWindowWins() {
        when(coreGateway.getSysConfig(GLOBAL_KEY)).thenReturn("120");
        when(coreGateway.getSysConfig(JUDGE_KEY)).thenReturn("600");
        assertEquals(Duration.ofSeconds(600), resolver.resolveJudgeQaGen());
    }
    /** Out-of-window values (bounds shared with the global key) degrade to the
     *  global face instead of pinning an unusable ceiling. */
    @Test
    void judgeKeyOutOfRange_fallsBackToGlobal() {
        when(coreGateway.getSysConfig(GLOBAL_KEY)).thenReturn("200");
        when(coreGateway.getSysConfig(JUDGE_KEY)).thenReturn("9");
        assertEquals(Duration.ofSeconds(200), resolver.resolveJudgeQaGen());

        when(coreGateway.getSysConfig(JUDGE_KEY)).thenReturn("901");
        assertEquals(Duration.ofSeconds(200), resolver.resolveJudgeQaGen());
    }

    /** Garbage judge values degrade through the same non-silent warn path. */
    @Test
    void judgeKeyGarbage_fallsBackToGlobal() {
        when(coreGateway.getSysConfig(JUDGE_KEY)).thenReturn("abc");
        when(coreGateway.getSysConfig(GLOBAL_KEY)).thenReturn("200");

        assertEquals(Duration.ofSeconds(200), resolver.resolveJudgeQaGen());
    }

    /** Shared seam contract: a ceiling shortens longer timeouts only and never
     *  lengthens a shorter one; null timeout keeps the ceiling itself (chain
     *  install default tightened the same way the doc plane composes it). */
    @Test
    void clampUnderCeiling_shortensOnlyNeverLengthens() {
        UnaryOperator<Duration> clamp = LlmTimeoutResolver.clampUnderCeiling(
                null, Duration.ofSeconds(120));

        assertEquals(Duration.ofSeconds(120), clamp.apply(Duration.ofSeconds(300)));
        assertEquals(Duration.ofSeconds(60), clamp.apply(Duration.ofSeconds(60)));
        assertEquals(Duration.ofSeconds(120), clamp.apply(null));
    }

    /** Shared seam contract: composing under an existing caller clamp takes the
     *  smaller of both faces — the ceiling never overrides a tighter deadline. */
    @Test
    void clampUnderCeiling_composesUnderExistingClamp() {
        UnaryOperator<Duration> deadline = t -> Duration.ofSeconds(30);
        UnaryOperator<Duration> clamp = LlmTimeoutResolver.clampUnderCeiling(
                deadline, Duration.ofSeconds(120));

        assertEquals(Duration.ofSeconds(30), clamp.apply(Duration.ofSeconds(300)));
    }
}
