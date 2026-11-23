package com.sixbean.testpike.aiservice.gen.domain.component;

import com.sixbean.testpike.aiservice.llm.api.dto.EmbeddingCompletionRequest;
import com.sixbean.testpike.aiservice.llm.domain.LlmEmbeddingResult;
import com.sixbean.testpike.aiservice.llm.domain.component.EmbeddingFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.EmbeddingProperties;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * segment B (unit face): per-item semantic reconciliation of the rule
 * ledger against the generated product text. The verdict reuses the
 * repository's single in-memory cosine source ({@link SemanticMatcher} over a
 * stubbed embedding chain — infrastructure isolation is L2-legal, mirroring
 * {@code CoverageCheckerSemanticTest}); every business judgement runs on real
 * classes. Bidirectional fixtures: a near-paraphrase product covers the rule
 * (no false red), an unrelated product does not (no false green); prose
 * candidates are booked for human adjudication and NEVER auto-judged; a chain
 * failure degrades honestly instead of fabricating similarities; the
 * covered test is {@code similarity >= floor} (boundary inclusive).
 */
class RuleLedgerReconcilerTest {

    private static final float[] PRODUCT = {1f, 1f, 0f};
    private static final float[] PARAPHRASE = {1f, 1f, 0.05f};
    private static final float[] UNRELATED = {0f, 0f, 1f};

    private final int[] chainCalls = {0};

    /** reconciler over a text→vector stub chain (last input = product text). */
    private RuleLedgerReconciler reconciler(Map<String, float[]> table) {
        EmbeddingFallbackChain chain = mock(EmbeddingFallbackChain.class);
        when(chain.embed(any(EmbeddingCompletionRequest.class), anyLong())).thenAnswer(inv -> {
            chainCalls[0]++;
            EmbeddingCompletionRequest request = inv.getArgument(0);
            List<float[]> vectors = new ArrayList<>();
            for (String text : request.input()) {
                vectors.add(table.getOrDefault(text, UNRELATED));
            }
            return new LlmEmbeddingResult(vectors, "stub", "test-embed", 0, 0L);
        });
        EmbeddingProperties properties = new EmbeddingProperties();
        properties.setModel("test-embed");
        return new RuleLedgerReconciler(new RuleItemExtractor(),
                new SemanticMatcher(chain, properties));
    }

    private static RuleSourceBlock block(long chunkId, String text) {
        return new RuleSourceBlock(chunkId, text);
    }

    /** One rule table whose data row the stub maps via criterionText. */
    private static List<RuleSourceBlock> tableBlock(String field, long chunkId) {
        String text = "| 字段 | 输入类型 | 必填 | 限制 |\n"
                + "| --- | --- | --- | --- |\n"
                + "| " + field + " | 文本 | 是 | 不超过20字 |\n";
        return List.of(block(chunkId, text));
    }

    @Test
    void nearParaphraseProductCoversTheRuleAndProducesNoFeedback() {
        RuleLedgerReconciler reconciler = reconciler(Map.of(
                "手机号 文本 是 不超过20字", PARAPHRASE,
                "product", PRODUCT));
        RuleLedgerOutcome outcome = reconciler.reconcile(
                tableBlock("手机号", 148477L), "product", 7L, 0.5);

        assertFalse(outcome.embeddingDegraded());
        assertEquals(1, chainCalls[0]);
        assertEquals(1, outcome.verdicts().size());
        assertTrue(outcome.verdicts().get(0).covered());
        assertEquals(0, outcome.missingCount());
        assertTrue(outcome.feedbackLines().isEmpty());
    }

    @Test
    void unrelatedProductLeavesTheRuleMissingAndNamesItemAndChunk() {
        RuleLedgerReconciler reconciler = reconciler(Map.of("product", PRODUCT));
        RuleLedgerOutcome outcome = reconciler.reconcile(
                tableBlock("手机号", 148477L), "product", 7L, 0.5);

        assertEquals(1, outcome.missingCount());
        assertEquals(0, outcome.coveredCount());
        assertEquals(1, outcome.feedbackLines().size());
        String line = outcome.feedbackLines().get(0);
        assertTrue(line.contains("手机号"), line);
        assertTrue(line.contains("148477"), line);
    }

    @Test
    void similarityExactlyAtFloorCountsAsCovered() {
        // Collinear stub vectors give an exact 1.0 cosine (2 / (1 x 2)) —
        // identical unit vectors would land on 0.9999999999999998 because
        // sqrt(2)*sqrt(2) != 2 in double arithmetic.
        RuleLedgerReconciler reconciler = reconciler(Map.of(
                "手机号 文本 是 不超过20字", new float[]{1f, 0f, 0f},
                "product", new float[]{2f, 0f, 0f}));

        assertEquals(1, reconciler.reconcile(
                tableBlock("手机号", 1L), "product", 7L, 1.0).coveredCount(),
                "boundary floor is inclusive (>=)");
        assertEquals(0, reconciler.reconcile(
                tableBlock("手机号", 1L), "product", 7L, 1.0001).coveredCount(),
                "a floor strictly above the similarity stays uncovered");
    }

    @Test
    void proseCandidatesAreBookedButNeverAutoJudgedAndCostNoOutbound() {
        RuleLedgerReconciler reconciler = reconciler(Map.of("product", PRODUCT));
        RuleLedgerOutcome outcome = reconciler.reconcile(
                List.of(block(9L, "密码必须包含字母和数字。")), "product", 7L, 0.5);

        assertEquals(0, chainCalls[0], "prose-only ledger must not embed anything");
        assertEquals(1, outcome.proseCandidateCount());
        assertTrue(outcome.verdicts().isEmpty());
        assertTrue(outcome.feedbackLines().isEmpty(),
                "prose candidates await human adjudication, never auto feedback");
    }

    @Test
    void chainFailureDegradesHonestlyWithoutFabricatingVerdicts() {
        EmbeddingFallbackChain chain = mock(EmbeddingFallbackChain.class);
        when(chain.embed(any(EmbeddingCompletionRequest.class), anyLong()))
                .thenThrow(new ServiceException("embedding 链不可用", BizErrorCode.AI_PROVIDER_ERROR));
        EmbeddingProperties properties = new EmbeddingProperties();
        properties.setModel("test-embed");
        RuleLedgerReconciler reconciler = new RuleLedgerReconciler(new RuleItemExtractor(),
                new SemanticMatcher(chain, properties));

        RuleLedgerOutcome outcome = reconciler.reconcile(
                tableBlock("手机号", 1L), "product", 7L, 0.5);

        assertTrue(outcome.embeddingDegraded());
        assertTrue(outcome.verdicts().isEmpty(), "degraded outcome carries no fake verdicts");
        assertTrue(outcome.feedbackLines().isEmpty(),
                "degraded outcome keeps the legacy judgement untouched");
    }

    @Test
    void emptyBlockSetIsAnEmptyLedgerWithZeroOutbound() {
        RuleLedgerReconciler reconciler = reconciler(Map.of());

        assertTrue(reconciler.reconcile(List.of(), "product", 7L, 0.5).verdicts().isEmpty());
        assertTrue(reconciler.reconcile(null, "product", 7L, 0.5).verdicts().isEmpty());
        assertEquals(0, chainCalls[0]);
    }

    @Test
    void mixedLedgerKeepsExtractionOrderAndBooksProseAlongsideRowVerdicts() {
        String blockText = "| 字段 | 必填 | 限制 |\n"
                + "| --- | --- | --- |\n"
                + "| 手机号 | 是 | 11位 |\n"
                + "| 验证码 | 是 | 6位 |\n\n密码必须包含字母和数字。\n";
        RuleLedgerReconciler reconciler = reconciler(Map.of(
                "手机号 是 11位", PARAPHRASE,
                "product", PRODUCT));

        RuleLedgerOutcome outcome = reconciler.reconcile(
                List.of(block(42L, blockText)), "product", 7L, 0.5);

        assertEquals(2, outcome.verdicts().size());
        assertEquals("手机号", outcome.verdicts().get(0).item().field());
        assertEquals("验证码", outcome.verdicts().get(1).item().field());
        assertTrue(outcome.verdicts().get(0).covered());
        assertFalse(outcome.verdicts().get(1).covered());
        assertEquals(1, outcome.proseCandidateCount());
        assertEquals(1, outcome.feedbackLines().size());
        assertTrue(outcome.feedbackLines().get(0).contains("验证码"));
    }
}
