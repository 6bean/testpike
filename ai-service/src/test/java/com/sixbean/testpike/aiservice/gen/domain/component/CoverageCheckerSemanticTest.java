package com.sixbean.testpike.aiservice.gen.domain.component;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.EmbeddingCompletionRequest;
import com.sixbean.testpike.aiservice.llm.domain.LlmEmbeddingResult;
import com.sixbean.testpike.aiservice.llm.domain.component.EmbeddingFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.EmbeddingProperties;
import com.sixbean.testpike.aiservice.testsupport.LogbackSink;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * the A5 acceptance-coverage verdict upgrades from the
 * head/tail fragment hit to a semantic reconciliation — every criterion and
 * the case aggregate text ride ONE batched embedding round-trip through
 * {@link EmbeddingFallbackChain} (the only outbound, mock mode fails closed),
 * and cosine {@code >= ai.gen.coverage-semantic-threshold} marks the item
 * covered. Bidirectional fixtures (near-paraphrase vs unrelated) prove the
 * verdict is semantic, not the old fragment rule wearing a new coat; a chain
 * failure degrades the whole check to the legacy fragment shape with one
 * WARN naming the task, and the off switch equals the legacy behavior with
 * zero outbound calls.
 * <p>Embedding vectors are a deterministic table stub at the chain boundary
 * (infrastructure isolation is L2-legal per the segment contract); every
 * business judgment below runs on real classes.
 */
class CoverageCheckerSemanticTest {

    private static final float[] AGG = {1f, 1f, 0f};
    private static final float[] ORTHO = {0f, 0f, 1f};

    /** checker with a live semantic branch (gateway stub + real matcher). */
    private CoverageChecker semanticChecker(EmbeddingFallbackChain chain,
                                            CoreGateway gateway) {
        EmbeddingProperties properties = new EmbeddingProperties();
        properties.setModel("test-embed");
        return new CoverageChecker(gateway, new SemanticMatcher(chain, properties));
    }

    /** stub answering embed() from a text→vector table, last input = aggregate. */
    private EmbeddingFallbackChain stubChain(Map<String, float[]> table, int[] calls) {
        EmbeddingFallbackChain chain = mock(EmbeddingFallbackChain.class);
        when(chain.embed(any(EmbeddingCompletionRequest.class), anyLong())).thenAnswer(inv -> {
            calls[0]++;
            EmbeddingCompletionRequest request = inv.getArgument(0);
            List<float[]> vectors = new ArrayList<>();
            for (String text : request.input()) {
                vectors.add(table.getOrDefault(text, ORTHO));
            }
            return new LlmEmbeddingResult(vectors, "stub", "test-embed", 0, 0L);
        });
        return chain;
    }

    private CoreGateway gatewayOn(String threshold) {
        CoreGateway gateway = mock(CoreGateway.class);
        when(gateway.getSysConfig(CoverageChecker.SEMANTIC_ENABLED_KEY)).thenReturn("true");
        when(gateway.getSysConfig(CoverageChecker.SEMANTIC_THRESHOLD_KEY)).thenReturn(threshold);
        return gateway;
    }

    @Test
    void check_近义改写零片段命中_语义判覆盖且旧判据必漏() {
        String criteria = "登录成功后三秒内跳转首页";
        // paraphrase carries none of the head/tail 6-char fragments — the
        // legacy rule MUST miss it, the cosine verdict MUST catch it
        String caseText = "用户完成登录之后页面会在三秒钟之内自动进入首页";
        Map<String, float[]> table = new HashMap<>();
        table.put(criteria, AGG);
        table.put(caseText, AGG);
        int[] calls = new int[1];
        EmbeddingFallbackChain chain = stubChain(table, calls);
        CoreGateway gateway = gatewayOn("0.82");

        CoverageResult semantic = semanticChecker(chain, gateway)
                .check(criteria, caseText, 3L, 901L);

        assertTrue(semantic.passed(), "近义改写应被语义对账判为覆盖");
        assertEquals(1, semantic.coveredCount());

        // detection proof: same inputs on the legacy shape miss the criterion
        CoverageResult legacy = new CoverageChecker(null, null)
                .check(criteria, caseText, 3L, 901L);
        assertFalse(legacy.passed());
        assertEquals(List.of(criteria), legacy.uncovered());

        // one check = one batched round-trip: n items + the aggregate text
        assertEquals(1, calls[0], "一次覆盖对账只允许一轮 embedding 批量往返");
        ArgumentCaptor<EmbeddingCompletionRequest> captor =
                ArgumentCaptor.forClass(EmbeddingCompletionRequest.class);
        verify(chain, times(1)).embed(captor.capture(), eq(3L));
        List<String> inputs = captor.getValue().input();
        assertEquals(2, inputs.size());
        assertEquals(caseText, inputs.get(inputs.size() - 1), "聚合文本必须随条目同批出站");
    }

    @Test
    void check_无关长文语义判未覆盖() {
        String criteria = "优惠券满减金额校验";
        String caseText = "系统支持多语言界面切换，并提供深色与浅色主题设置入口";
        Map<String, float[]> table = new HashMap<>();
        table.put(criteria, AGG);
        // unrelated aggregate stays orthogonal → cosine 0 < threshold
        table.put(caseText, ORTHO);
        int[] calls = new int[1];

        CoverageResult result = semanticChecker(stubChain(table, calls), gatewayOn("0.82"))
                .check(criteria, caseText, 3L, 902L);

        assertFalse(result.passed());
        assertEquals(List.of(criteria), result.uncovered());
        assertEquals(0, result.coveredCount());
    }

    @Test
    void check_链不可用_整条对账回落旧片段语义并WARN点名任务() {
        String criteria = "账号密码正确可登录\n优惠券满减校验";
        String caseText = "账号密码正确可登录后进入首页";
        EmbeddingFallbackChain chain = mock(EmbeddingFallbackChain.class);
        when(chain.embed(any(EmbeddingCompletionRequest.class), anyLong()))
                .thenThrow(new ServiceException("embedding 服务不可用：mock 替身模式",
                        BizErrorCode.AI_PROVIDER_ERROR));
        CoreGateway gateway = gatewayOn("0.82");

        CoverageResult result;
        try (LogbackSink sink = LogbackSink.attach(CoverageChecker.class)) {
            result = semanticChecker(chain, gateway).check(criteria, caseText, 3L, 907L);
            assertTrue(sink.warns().stream().anyMatch(w -> w.contains("taskId=907")
                            && w.contains("回落")),
                    "降级必须 WARN 点名 taskId 并如实标注回落，实际=" + sink.warns());
        }

        // zero drift: the degraded verdict is exactly the legacy fragment shape
        assertFalse(result.passed());
        assertEquals(1, result.coveredCount());
        assertEquals(List.of("优惠券满减校验"), result.uncovered());
    }

    @Test
    void check_开关关闭_旧行为等值且零外呼() {
        String criteria = "登录成功后三秒内跳转首页";
        String caseText = "用户完成登录之后页面会在三秒钟之内自动进入首页";
        EmbeddingFallbackChain chain = mock(EmbeddingFallbackChain.class);
        CoreGateway gateway = mock(CoreGateway.class);
        when(gateway.getSysConfig(CoverageChecker.SEMANTIC_ENABLED_KEY)).thenReturn("false");

        CoverageResult result = semanticChecker(chain, gateway)
                .check(criteria, caseText, 3L, 903L);

        assertFalse(result.passed(), "off 态=旧片段判据，近义改写维持未覆盖");
        assertEquals(List.of(criteria), result.uncovered());
        verify(chain, never()).embed(any(), anyLong());
    }

    @Test
    void check_阈值热更现读_判定线随每轮配置生效() {
        String criteria = "优惠券满减金额校验";
        String caseText = "结算页自动核销满足门槛的优惠券并展示抵扣金额";
        float[] nearItem = {0.8f, 0.6f, 0f}; // cosine with the unit aggregate = 0.80
        float[] aggregate = {1f, 0f, 0f};
        Map<String, float[]> table = new HashMap<>();
        table.put(criteria, nearItem);
        table.put(caseText, aggregate);

        // absent threshold row → seeded 0.82 → 0.80 misses
        CoreGateway gatewayDefault = gatewayOn(null);
        CoverageResult withDefault = semanticChecker(stubChain(table, new int[1]), gatewayDefault)
                .check(criteria, caseText, 3L, 904L);
        assertFalse(withDefault.passed());

        // hot-updated 0.75 in the same round → same vectors now cover
        CoreGateway gatewayLoose = gatewayOn("0.75");
        CoverageResult withLoose = semanticChecker(stubChain(table, new int[1]), gatewayLoose)
                .check(criteria, caseText, 3L, 905L);
        assertTrue(withLoose.passed(), "阈值热更必须当轮现读生效");
    }

    @Test
    void check_空验收标准_不阻断旧契约零改() {
        EmbeddingFallbackChain chain = mock(EmbeddingFallbackChain.class);
        CoverageResult result = semanticChecker(chain, gatewayOn("0.82"))
                .check("", "任何用例内容", 3L, 906L);

        assertTrue(result.passed());
        assertTrue(result.noCriteria());
        verify(chain, never()).embed(any(), anyLong());
    }
}
