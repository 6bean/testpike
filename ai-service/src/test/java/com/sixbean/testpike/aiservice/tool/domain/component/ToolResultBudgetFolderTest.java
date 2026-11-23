package com.sixbean.testpike.aiservice.tool.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.aichat.domain.component.AiCtxConfigResolver;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.ToolFcAssemblyRemoteItem;
import com.sixbean.testpike.aiservice.llm.domain.LlmToolCall;
import com.sixbean.testpike.aiservice.llm.domain.component.TokenEstimator;
import com.sixbean.testpike.aiservice.llm.domain.component.TokenEstimatorRegistry;
import com.sixbean.testpike.aiservice.tool.domain.FcPlan;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AP-1(1) layered tool-result intake .
 * <p>The dispatcher backfills every successful tool output verbatim today, so
 * one oversized result inflates the whole ReAct dialog packet. This suite pins
 * the downgrade contract of {@link ToolResultBudgetFolder}: outputs within the
 * hot key {@code ai.ctx.tool-block-max-tokens} ride through untouched (zero
 * drift, same instance), oversized outputs become a head/tail window plus a
 * retrieval anchor while the full text goes to the externalizer seam.
 * <p>Degrade semantics (no silent loss, no thrown failure): externalizer
 * absent or throwing still yields the window with an honest "storage
 * unavailable" note; success/failure flags never change; the folded block
 * always fits the budget it violated (cl100k estimator, same engine family
 * the folder uses internally).
 * <p>L2 scope only: the real S3 grant link and the retrieval meta-tool are
 * separate slices; here the seam is a stub capturing what was externalized.
 */
class ToolResultBudgetFolderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Long ACTOR_ID = 9L;
    private static final Long PROJECT_SLICE = 7L;
    private static final int BUDGET = 200;

    private static final String REPEAT = "alpha bravo charlie delta echo ";
    private static final int REPEATS_FIT = 8;          // way below budget
    private static final int REPEATS_OVER = 4000;      // ~25k tokens

    private static String text(int repeats) {
        return REPEAT.repeat(repeats);
    }

    private static AiCtxConfigResolver resolverWithBudget(int budget) {
        AiCtxConfigResolver resolver = mock(AiCtxConfigResolver.class);
        when(resolver.toolBlockMaxTokens()).thenReturn(budget);
        return resolver;
    }

    @Test
    void underBudgetResultPassesThroughUntouched() {
        ToolResultBudgetFolder folder =
                new ToolResultBudgetFolder(resolverWithBudget(BUDGET), Optional.empty());
        ToolCallOutcome ok = ToolCallOutcome.ok("t1", text(REPEATS_FIT));
        assertSame(ok, folder.bound(ok, 1L, PROJECT_SLICE));
    }

    @Test
    void shortFailureMessagePassesThroughUntouched() {
        ToolResultBudgetFolder folder =
                new ToolResultBudgetFolder(resolverWithBudget(BUDGET), Optional.empty());
        ToolCallOutcome fail = ToolCallOutcome.fail("t1", "工具「t1」执行失败：参数缺失");
        assertSame(fail, folder.bound(fail, 1L, PROJECT_SLICE));
    }

    @Test
    void overBudgetResultBecomesWindowWithRetrievalAnchor() {
        AtomicReference<String> stored = new AtomicReference<>();
        ToolResultExternalizer seam = (fullText, taskId, projectId) -> {
            stored.set(fullText);
            return "a-7";
        };
        ToolResultBudgetFolder folder = new ToolResultBudgetFolder(
                resolverWithBudget(BUDGET), Optional.of(seam));
        String original = text(REPEATS_OVER);
        ToolCallOutcome folded = folder.bound(ToolCallOutcome.ok("big.tool", original),
                42L, PROJECT_SLICE);

        assertTrue(folded.success(), "folding must never flip the success flag");
        assertEquals(original, stored.get(), "externalizer must receive the verbatim full text");
        assertTrue(folded.content().startsWith(REPEAT), "head window kept");
        assertTrue(folded.content().endsWith(REPEAT), "tail window kept verbatim");
        assertTrue(folded.content().contains("中间省略"), "model-readable elision marker");
        assertTrue(folded.content().contains("anchor=a-7"), "pointer carries the anchor id");
        assertTrue(folded.content().contains("retrieval"), "pointer names the retrieval meta-tool");
        assertFalse(folded.content().contains("全文落盘暂不可用"),
                "anchor path must not advertise the unavailable note");
        assertTrue(folded.content().length() < original.length() / 10,
                "oversized block actually shrank");
        TokenEstimator estimator = TokenEstimatorRegistry.resolve("cl100k", null);
        assertTrue(estimator.countTokens(folded.content()) <= BUDGET,
                "folded block fits the budget it violated");
    }

    @Test
    void missingExternalizerStillWindowsWithHonestNote() {
        ToolResultBudgetFolder folder =
                new ToolResultBudgetFolder(resolverWithBudget(BUDGET), Optional.empty());
        ToolCallOutcome folded = folder.bound(ToolCallOutcome.ok("big.tool", text(REPEATS_OVER)),
                null, null);
        assertTrue(folded.success());
        assertTrue(folded.content().contains("全文落盘暂不可用"),
                "absent storage seam is declared, never silent");
        assertFalse(folded.content().contains("anchor="), "no anchor without storage");
    }

    @Test
    void throwingExternalizerDegradesToWindowNotFailure() {
        ToolResultExternalizer boom = (fullText, taskId, projectId) -> {
            throw new IllegalStateException("storage grant refused");
        };
        ToolResultBudgetFolder folder = new ToolResultBudgetFolder(
                resolverWithBudget(BUDGET), Optional.of(boom));
        ToolCallOutcome folded = folder.bound(ToolCallOutcome.ok("big.tool", text(REPEATS_OVER)),
                null, null);
        assertTrue(folded.success(), "a storage incident must not poison the tool result");
        assertTrue(folded.content().contains("全文落盘暂不可用"));
    }

    @Test
    void oversizedFailureMessageIsAlsoBounded() {
        ToolResultBudgetFolder folder =
                new ToolResultBudgetFolder(resolverWithBudget(BUDGET), Optional.empty());
        ToolCallOutcome folded = folder.bound(
                ToolCallOutcome.fail("big.tool", text(REPEATS_OVER)), null, null);
        assertFalse(folded.success(), "failure flag survives folding");
        assertTrue(folded.content().contains("全文落盘暂不可用"));
    }

    @Test
    void dispatcherFoldsOversizedAnalysisOutputAtBackfill() {
        CoreGateway coreGateway = mock(CoreGateway.class);
        Map<String, Object> big = new LinkedHashMap<>();
        big.put("blob", text(REPEATS_OVER));
        when(coreGateway.runAnalysis(anyLong(), anyLong(), eq("bigView"), anyString()))
                .thenReturn(big);

        ToolResultExternalizer seam = (fullText, taskId, projectId) -> "a-9";
        ToolResultBudgetFolder folder = new ToolResultBudgetFolder(
                resolverWithBudget(BUDGET), Optional.of(seam));
        ToolDispatcher dispatcher = new ToolDispatcher(coreGateway, MAPPER, folder);

        Map<String, ToolFcAssemblyRemoteItem> index = new LinkedHashMap<>();
        index.put("分析·大结果", new ToolFcAssemblyRemoteItem(91, "分析·大结果", 2, 5,
                "{\"type\":\"object\"}", 30000, false, "bigView"));
        FcPlan plan = new FcPlan(List.of(), Map.copyOf(index));

        ToolCallOutcome outcome = dispatcher.dispatch(plan,
                new LlmToolCall("call-1", "分析·大结果", "{}"),
                ACTOR_ID, null, null, null, PROJECT_SLICE);

        assertTrue(outcome.success());
        assertTrue(outcome.content().contains("anchor=a-9"),
                "dispatcher-level backfill goes through the budget folder");
        TokenEstimator estimator = TokenEstimatorRegistry.resolve("cl100k", null);
        assertTrue(estimator.countTokens(outcome.content()) <= BUDGET);
    }

    @Test
    void nullAnchorExternalizerReturnIsTreatedAsUnavailable() {
        ToolResultExternalizer noAnchor = (fullText, taskId, projectId) -> null;
        ToolResultBudgetFolder folder = new ToolResultBudgetFolder(
                resolverWithBudget(BUDGET), Optional.of(noAnchor));
        ToolCallOutcome folded = folder.bound(ToolCallOutcome.ok("big.tool", text(REPEATS_OVER)),
                null, null);
        assertTrue(folded.content().contains("全文落盘暂不可用"),
                "null anchor means nothing was stored — say so honestly");
        assertFalse(folded.content().contains("anchor="));
    }
}
