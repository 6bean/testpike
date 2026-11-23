package com.sixbean.testpike.aiservice.aichat.domain.component;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AP-1 six-layer chain, reader face: ai.ctx.* K8 hot keys win over the
 * configuration-file fallback; garbage / out-of-range values never reach the
 * consumer (fallback to properties default, warn logged). Mirrors the
 * KnowledgeSearchConfigResolver discipline.
 */
class AiCtxConfigResolverTest {

    private static AiCtxConfigResolver resolverWith(String key, String value) {
        CoreGateway gateway = mock(CoreGateway.class);
        when(gateway.getSysConfig(key)).thenReturn(value);
        return new AiCtxConfigResolver(gateway, new AiCtxProperties());
    }

    @Test
    void keepRecentTurnsReadsK8ValueHot() {
        AiCtxConfigResolver r = resolverWith(AiCtxConfigResolver.KEY_SESSION_KEEP_RECENT_TURNS, "12");
        assertEquals(12, r.sessionKeepRecentTurns());
    }

    @Test
    void keepRecentTurnsFallsBackOnGarbageOrOutOfRange() {
        assertEquals(6, resolverWith(AiCtxConfigResolver.KEY_SESSION_KEEP_RECENT_TURNS, "abc")
                .sessionKeepRecentTurns());
        assertEquals(6, resolverWith(AiCtxConfigResolver.KEY_SESSION_KEEP_RECENT_TURNS, "-5")
                .sessionKeepRecentTurns(), "below floor falls back, never 0-window");
        assertEquals(6, resolverWith(AiCtxConfigResolver.KEY_SESSION_KEEP_RECENT_TURNS, "99999")
                .sessionKeepRecentTurns(), "above ceiling falls back");
    }

    @Test
    void missingKeyUsesFactoryDefaults() {
        CoreGateway gateway = mock(CoreGateway.class);
        AiCtxConfigResolver r = new AiCtxConfigResolver(gateway, new AiCtxProperties());
        assertEquals(6, r.sessionKeepRecentTurns());
        assertEquals(2000, r.toolBlockMaxTokens());
        assertEquals(30, r.memoryMaxItems());
    }

    @Test
    void toolBlockWindowHonoursK8AndRange() {
        assertEquals(5000, resolverWith(AiCtxConfigResolver.KEY_TOOL_BLOCK_MAX_TOKENS, "5000")
                .toolBlockMaxTokens());
        assertEquals(2000, resolverWith(AiCtxConfigResolver.KEY_TOOL_BLOCK_MAX_TOKENS, "10")
                .toolBlockMaxTokens(), "below 200 floor falls back");
    }

    @Test
    void memoryItemsHonoursK8AndRange() {
        assertEquals(15, resolverWith(AiCtxConfigResolver.KEY_MEMORY_MAX_ITEMS, "15").memoryMaxItems());
        assertEquals(30, resolverWith(AiCtxConfigResolver.KEY_MEMORY_MAX_ITEMS, "0").memoryMaxItems());
    }

    /**
     * DD dup-clear ④: the heal switch reads through the single-source
     * SysConfigBooleans — every honest spelling works (case/padding free),
     * absent/blank/dirty rows fall back to the file tier and never flip it.
     */
    @Test
    void healEnabledReadsEveryLegalSpellingAndFallsBackOnDirty() {
        AiCtxProperties props = new AiCtxProperties();
        boolean fileTier = props.isHealOverBudget();
        assertEquals(true, resolverWith(AiCtxConfigResolver.KEY_HEAL_ENABLED, "1").healEnabled());
        assertEquals(true, resolverWith(AiCtxConfigResolver.KEY_HEAL_ENABLED, " TRUE ").healEnabled());
        assertEquals(false, resolverWith(AiCtxConfigResolver.KEY_HEAL_ENABLED, "0").healEnabled());
        assertEquals(false, resolverWith(AiCtxConfigResolver.KEY_HEAL_ENABLED, "False").healEnabled());
        assertEquals(fileTier, resolverWith(AiCtxConfigResolver.KEY_HEAL_ENABLED, null).healEnabled(),
                "absent row keeps the file tier");
        assertEquals(fileTier, resolverWith(AiCtxConfigResolver.KEY_HEAL_ENABLED, "  ").healEnabled(),
                "blank row keeps the file tier");
        assertEquals(fileTier, resolverWith(AiCtxConfigResolver.KEY_HEAL_ENABLED, "yes").healEnabled(),
                "dirty row loses only its override, never flips the switch");
    }
}
