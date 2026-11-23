package com.sixbean.testpike.aiservice.llm.domain.component;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;


class HfBpeColdLoadPerfTest {

    @Test
    @DisplayName("P1 两份词表冷加载各自 ≤15s 且二次解析走缓存（构建成本只付一次）")
    void coldLoadWithinBudgetAndCached() {
        long t0 = System.nanoTime();
        TokenEstimator qwen = TokenEstimatorRegistry.resolve("qwen", "m");
        long qwenMs = (System.nanoTime() - t0) / 1_000_000;

        long t1 = System.nanoTime();
        TokenEstimator ds = TokenEstimatorRegistry.resolve("deepseek", "m");
        long dsMs = (System.nanoTime() - t1) / 1_000_000;

        long t2 = System.nanoTime();
        TokenEstimatorRegistry.resolve("qwen", "m");
        long warmMs = (System.nanoTime() - t2) / 1_000_000;

        System.out.println("PERF cold qwen=" + qwenMs + "ms deepseek=" + dsMs + "ms warm=" + warmMs + "ms");
        assertTrue(qwenMs <= 15_000, "qwen 冷加载超预算: " + qwenMs + "ms");
        assertTrue(dsMs <= 15_000, "deepseek 冷加载超预算: " + dsMs + "ms");
        assertTrue(warmMs < 50, "二次解析未命中缓存: " + warmMs + "ms");
        assertTrue(qwen.countTokens("结算分账规则说明") > 0);
        assertTrue(ds.countTokens("结算分账规则说明") > 0);
    }

    @Test
    @DisplayName("P2 热路径计数吞吐：2000 字文档 500 次 ≤5s（guard/封组调用频度实测锚）")
    void hotPathThroughput() {
        TokenEstimator est = TokenEstimatorRegistry.resolve("qwen", "m");
        StringBuilder sb = new StringBuilder();
        while (sb.length() < 2000) {
            sb.append("订单分账规则第").append(sb.length()).append("条：按金额比例 T+1 清分，");
        }
        String doc = sb.substring(0, 2000);
        long start = System.nanoTime();
        for (int i = 0; i < 500; i++) {
            est.countTokens(doc);
        }
        long ms = (System.nanoTime() - start) / 1_000_000;
        System.out.println("PERF hot 500x2000chars = " + ms + "ms");
        assertTrue(ms <= 5_000, "热路径吞吐不足: " + ms + "ms");
    }
}
