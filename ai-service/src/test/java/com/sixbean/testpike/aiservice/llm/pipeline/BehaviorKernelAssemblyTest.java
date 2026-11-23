package com.sixbean.testpike.aiservice.llm.pipeline;

import com.sixbean.testpike.aiservice.aichat.domain.component.ChatBehaviorDirectives;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L0 behaviour-kernel assembly semantics (reasoning pipeline : every
 * reasoning consumer shares one clause-assembly source — the published
 * template row (or the caller's conservative fallback) is single-sourced from
 * the existing directive piece, and the failure-transparency/evidence-first
 * invariant rides along exactly once no matter which head shape came in.
 * An absent, blank or unreadable head never produces an empty prompt, and a
 * head that already carries the clause is never extended twice.
 */
class BehaviorKernelAssemblyTest {

    private static final String FALLBACK = "内置保守条款";

    private static CoreGateway.PromptTemplateBrief rowWith(String content) {
        return new CoreGateway.PromptTemplateBrief(1L,
                ChatBehaviorDirectives.TEMPLATE_NAME,
                ChatBehaviorDirectives.TEMPLATE_DOMAIN, 1, content, 2);
    }

    @Test
    void publishedTemplateContentRemainsTheHeadSource() {
        String head = BehaviorKernelAssembly.systemHead(rowWith("模板正文条款"), FALLBACK);
        assertTrue(head.contains("模板正文条款"),
                "the domain head is single-sourced from the template, not re-hardcoded");
        assertTrue(head.contains(BehaviorKernelAssembly.KERNEL_CLAUSE),
                "the invariant clause rides along on every assembled head");
    }

    @Test
    void missingBlankOrContentlessRowDegradesToFallbackPlusInvariant() {
        for (CoreGateway.PromptTemplateBrief row : new CoreGateway.PromptTemplateBrief[]{
                null, rowWith(null), rowWith("   ")}) {
            String head = BehaviorKernelAssembly.systemHead(row, FALLBACK);
            assertTrue(head.contains(FALLBACK),
                    "a broken row never drops the conservative fallback");
            assertTrue(head.contains(BehaviorKernelAssembly.KERNEL_CLAUSE),
                    "the invariant still applies on the fallback shape");
        }
    }

    /** Prefix economics: the invariant clause is identical on every call of
     * every domain, so it must sit at the very front of the request head —
     * a stable leading block is the shape provider prompt caches can hit.
     * Domain-specific template text follows it, never precedes it. */
    @Test
    void kernelClauseIsTheSharedStablePrefixAcrossDomains() {
        String headA = BehaviorKernelAssembly.systemHead(rowWith("域甲条款正文"), FALLBACK);
        String headB = BehaviorKernelAssembly.systemHead(rowWith("域乙条款正文"), FALLBACK);
        assertTrue(headA.startsWith(BehaviorKernelAssembly.KERNEL_CLAUSE),
                "the stable clause opens the head so cross-call prefixes match");
        assertTrue(headB.startsWith(BehaviorKernelAssembly.KERNEL_CLAUSE),
                "every domain head shares the same cached prefix");
        assertTrue(headA.contains("域甲条款正文"), "the domain text still rides the head");
        assertTrue(headB.contains("域乙条款正文"), "the domain text still rides the head");
        assertEquals(headA.substring(0, BehaviorKernelAssembly.KERNEL_CLAUSE.length()),
                headB.substring(0, BehaviorKernelAssembly.KERNEL_CLAUSE.length()),
                "the leading block is byte-identical across domains");
    }

    @Test
    void assembledHeadIsNeverEmptyEvenWithoutAnyMaterial() {
        String head = BehaviorKernelAssembly.systemHead(null, "  ");
        assertEquals(BehaviorKernelAssembly.KERNEL_CLAUSE, head,
                "without template or fallback text the invariant alone still answers");
        assertFalse(head.isBlank());
    }

    @Test
    void invariantClauseIsPrefixedExactlyOnce() {
        String once = BehaviorKernelAssembly.prefixKernelClause("域条款");
        String twice = BehaviorKernelAssembly.prefixKernelClause(once);
        assertEquals(once, twice, "re-assembly never duplicates the invariant block");
        assertEquals(1, BehaviorKernelAssembly.kernelClauseCount(once));
    }

    @Test
    void switchSemanticsDelegateToTheDirectivePiece() {
        for (String raw : new String[]{null, "", "  ", "1", "true", "TRUE", "garbage"}) {
            assertEquals(ChatBehaviorDirectives.switchOn(raw),
                    BehaviorKernelAssembly.directivesEnabled(raw),
                    "tri-state gate semantics stay single-sourced for '" + raw + "'");
        }
        assertFalse(BehaviorKernelAssembly.directivesEnabled("0"));
        assertFalse(BehaviorKernelAssembly.directivesEnabled("false"));
    }

    /** single-source assembly: closed gate never touches the template read. */
    @Test
    void assembleHeadClosedGateSkipsTemplateReadAndKeepsInvariantOnce() {
        boolean[] consulted = {false};
        String head = BehaviorKernelAssembly.assembleHead("0", () -> {
            consulted[0] = true;
            return rowWith("不该被读到的模板行");
        }, "  内置保守条款  ", "降级测试前缀");
        assertFalse(consulted[0], "governance gate closed = the template endpoint is never consulted");
        assertTrue(head.contains("内置保守条款"), "source-layout indentation never reaches the model: " + head);
        assertEquals(1, BehaviorKernelAssembly.kernelClauseCount(head));
    }

    /** single-source assembly: a failing read degrades, warns, never breaks. */
    @Test
    void assembleHeadReadFailureDegradesToContractWithInvariantOnce() {
        String head = BehaviorKernelAssembly.assembleHead(null, () -> {
            throw new IllegalStateException("模板端点不可达");
        }, FALLBACK, "降级测试前缀");
        assertTrue(head.contains(FALLBACK), "degraded head keeps the conservative contract");
        assertEquals(1, BehaviorKernelAssembly.kernelClauseCount(head),
                "the invariant rides along exactly once on the degrade path");
    }

    /** single-source assembly: open gate + published row = row + invariant. */
    @Test
    void assembleHeadOpenGateUsesPublishedRow() {
        String head = BehaviorKernelAssembly.assembleHead("1", () -> rowWith("模板正文条款"),
                FALLBACK, "降级测试前缀");
        assertTrue(head.contains("模板正文条款"));
        assertEquals(1, BehaviorKernelAssembly.kernelClauseCount(head));
    }
}
