package com.sixbean.testpike.aiservice.aichat.domain.component;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behaviour-directive gate semantics : the switch is a
 * factory-on tri-state key — an absent row or an unparsable value keeps the
 * directives ON (only an explicit 0/false closes them), and the system head
 * is single-sourced from the published template row: a missing, blank or
 * whitespace-only row content falls back to the caller's conservative text,
 * never an empty prompt and never a broken turn.
 */
class ChatBehaviorDirectivesTest {

    private static final String FALLBACK = "内置保守兜底条款";

    @Test
    void absentUnparsableAndTrueValuesAllKeepTheDirectivesOn() {
        assertTrue(ChatBehaviorDirectives.switchOn(null), "absent row = factory on");
        assertTrue(ChatBehaviorDirectives.switchOn(""), "blank value = factory on");
        assertTrue(ChatBehaviorDirectives.switchOn("  "), "whitespace value = factory on");
        assertTrue(ChatBehaviorDirectives.switchOn("1"), "'1' reads as on");
        assertTrue(ChatBehaviorDirectives.switchOn("true"), "'true' reads as on");
        assertTrue(ChatBehaviorDirectives.switchOn("TRUE"), "case-insensitive on");
        assertTrue(ChatBehaviorDirectives.switchOn("garbage"),
                "an unparsable value must not silently switch an AI feature off");
    }

    @Test
    void onlyExplicitZeroOrFalseCloseTheDirectives() {
        assertFalse(ChatBehaviorDirectives.switchOn("0"), "'0' reads as off");
        assertFalse(ChatBehaviorDirectives.switchOn("false"), "'false' reads as off");
        assertFalse(ChatBehaviorDirectives.switchOn("FALSE"), "case-insensitive off");
    }

    @Test
    void publishedRowContentBecomesTheSystemHead() {
        CoreGateway.PromptTemplateBrief row =
                new CoreGateway.PromptTemplateBrief(1L,
                        ChatBehaviorDirectives.TEMPLATE_NAME,
                        ChatBehaviorDirectives.TEMPLATE_DOMAIN, 1,
                        "已发布的行为条款正文", 2);
        assertEquals("已发布的行为条款正文",
                ChatBehaviorDirectives.headOrFallback(row, FALLBACK),
                "the head is single-sourced from the template, not re-hardcoded");
    }

    @Test
    void missingBlankOrContentlessRowFallsBackToTheConservativeText() {
        assertEquals(FALLBACK, ChatBehaviorDirectives.headOrFallback(null, FALLBACK),
                "no published row = conservative fallback, the chain never breaks");
        assertEquals(FALLBACK, ChatBehaviorDirectives.headOrFallback(
                new CoreGateway.PromptTemplateBrief(1L, "n", "d", 1, null, 2), FALLBACK));
        assertEquals(FALLBACK, ChatBehaviorDirectives.headOrFallback(
                new CoreGateway.PromptTemplateBrief(1L, "n", "d", 1, "   ", 2), FALLBACK));
    }

    @Test
    void templateFamilyLiteralsMatchTheFactorySeed() {
        // The gateway read keys the published row by these exact literals —
        // a drift here silently disconnects the turn from the management page.
        assertEquals("ai.chat.behavior-directives.enabled",
                ChatBehaviorDirectives.KEY_ENABLED);
        assertEquals("chat-behavior-directives", ChatBehaviorDirectives.TEMPLATE_NAME);
        assertEquals("chat-behavior", ChatBehaviorDirectives.TEMPLATE_DOMAIN);
        assertEquals(2, ChatBehaviorDirectives.PUBLISHED_STATUS);
    }
}
