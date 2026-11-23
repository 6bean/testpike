package com.sixbean.testpike.aiservice.llm.pipeline;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;

import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Shared three-path probe for behaviour-kernel head wiring (R40 single
 * source): every upgraded domain proves the same contract — published
 * template row replaces the built-in contract, a closed governance gate
 * performs zero template reads, and a failing read degrades to the
 * built-in contract — with the kernel invariant appended exactly once.
 * Domain test classes feed this probe their gateway mock, their constant
 * triple and a head-assembly supplier instead of copy-pasting the
 * assertion bodies per leg.
 */
public final class BehaviorKernelHeadProbe {

    private BehaviorKernelHeadProbe() {
    }

    /** Gate open + published row present: the row heads the prompt, contract not stacked. */
    public static void publishedRowBecomesHeadAndClauseAppearsOnce(
            CoreGateway gateway, String governanceKey, String templateName,
            String templateDomain, Supplier<String> headAssembly, String builtinPrefix) {
        when(gateway.getSysConfig(governanceKey)).thenReturn("1");
        String published = "模板页定制口径指令（保留输出契约要求）。";
        when(gateway.promptTemplateLatestByStatus(templateName, templateDomain, 2))
                .thenReturn(new CoreGateway.PromptTemplateBrief(9L, templateName,
                        templateDomain, 1, published, 2));

        String head = headAssembly.get();

        assertTrue(head.contains(published), "已发布行=头正文: " + head);
        assertTrue(head.startsWith(BehaviorKernelAssembly.KERNEL_CLAUSE), "kernel clause leads as the stable cached prefix");
        assertFalse(head.contains(builtinPrefix), "模板在位=内置契约不叠加: " + head);
        assertEquals(1, BehaviorKernelAssembly.kernelClauseCount(head),
                "内核不变量恰一次: " + head);
    }

    /** Gate explicitly closed: zero template reads, built-in contract kept. */
    public static void closedGateSkipsTemplateReadAndKeepsContract(
            CoreGateway gateway, String governanceKey,
            Supplier<String> headAssembly, String builtinPrefix) {
        when(gateway.getSysConfig(governanceKey)).thenReturn("0");

        String head = headAssembly.get();

        verify(gateway, never()).promptTemplateLatestByStatus(any(), any(), anyInt());
        assertTrue(head.contains(builtinPrefix), "关态=内置契约头: " + head);
        assertEquals(1, BehaviorKernelAssembly.kernelClauseCount(head),
                "内核不变量恰一次: " + head);
    }

    /** Gate open + failing read: degrade to built-in contract, run never breaks. */
    public static void failingReadDegradesToContractWithoutBreaking(
            CoreGateway gateway, String governanceKey,
            Supplier<String> headAssembly, String builtinPrefix) {
        when(gateway.getSysConfig(governanceKey)).thenReturn(null);
        when(gateway.promptTemplateLatestByStatus(any(), any(), anyInt()))
                .thenThrow(new RuntimeException("core template endpoint unavailable"));

        // assembly.get() itself proves "never breaks": a thrown exception fails the test
        String head = headAssembly.get();

        assertTrue(head.contains(builtinPrefix), "降级=内置契约头: " + head);
        assertEquals(1, BehaviorKernelAssembly.kernelClauseCount(head),
                "降级路径不变量仍恰一次: " + head);
    }
}
