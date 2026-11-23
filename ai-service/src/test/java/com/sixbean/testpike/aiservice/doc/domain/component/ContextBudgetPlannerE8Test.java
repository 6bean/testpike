package com.sixbean.testpike.aiservice.doc.domain.component;

import com.sixbean.testpike.aiservice.llm.domain.component.TokenCounter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class ContextBudgetPlannerE8Test {

    private final TokenCounter counter = new TokenCounter();

    private static Map<String, Object> chunk(int seq, int contentChars) {
        return Map.of("seq", seq, "chapter", "章节" + seq, "content", "字".repeat(contentChars));
    }

    private ContextBudgetPlanner planner(int tokenBudget) {
        return new ContextBudgetPlanner(tokenBudget).withTokenCounter(counter);
    }

 
    @Test
    void needsCompression_token口径与字符口径中文方向() {
        ContextBudgetPlanner p = planner(512);
        List<Map<String, Object>> chunks = List.of(chunk(1, 500));
        assertTrue(p.needsCompression(chunks), "字符口径：500>480 应判压缩（T132 基线漂移守卫）");
        assertFalse(p.needsCompressionByTokens(chunks), "token 口径：≈375≤512 应判不压缩");
    }

    
    @Test
    void planGroupsByTokens_贪心封组token域() {
        ContextBudgetPlanner p = planner(512);
        List<Map<String, Object>> chunks = IntStream.rangeClosed(1, 3)
                .mapToObj(i -> chunk(i, 400)).toList();
        List<List<Map<String, Object>>> groups = p.planGroupsByTokens(chunks);
        assertEquals(List.of(1, 1, 1), groups.stream().map(List::size).toList());
        assertEquals(List.of(1, 2, 3), groups.stream().flatMap(List::stream)
                .map(c -> ((Number) c.get("seq")).intValue()).collect(Collectors.toList()));
    }

    
    @Test
    void planGroupsByTokens_未注入退化字符口径() {
        ContextBudgetPlanner p = new ContextBudgetPlanner(512);
        List<Map<String, Object>> chunks = IntStream.rangeClosed(1, 5)
                .mapToObj(i -> chunk(i, 100)).toList();
        List<List<Map<String, Object>>> groups = p.planGroupsByTokens(chunks);
        assertEquals(List.of(4, 1), groups.stream().map(List::size).toList());
    }

    
    @Test
    void summaryBudgetChars_同源clamp() {
        ContextBudgetPlanner p = planner(5120);
        p = p.withPolicy(BudgetPolicy.defaults());
        assertEquals(960, p.summaryBudgetChars(5120));
        assertEquals(281, p.summaryBudgetChars(1000));
        assertEquals(1406, p.summaryBudgetChars(99999));
    }


    private MultiLevelReducePlanner reduce(int trigger, int levels) {
        return new MultiLevelReducePlanner(new BudgetPolicy(0.2, 300, 1500, trigger, levels));
    }

 
    @Test
    void reducePlan_触发线以下单层() {
        ReducePlan plan = reduce(6, 2).plan(6);
        assertEquals(1, plan.levels());
        assertEquals(List.of(6), plan.levelGroupCounts());
        assertEquals(6, plan.totalSummaryCalls());
        assertEquals(0, plan.reduceCalls());
    }

 
    @Test
    void reducePlan_七组两级调用九() {
        ReducePlan plan = reduce(6, 2).plan(7);
        assertEquals(2, plan.levels());
        assertEquals(List.of(7, 2), plan.levelGroupCounts());
        assertEquals(9, plan.totalSummaryCalls());
    }

 
    @Test
    void reducePlan_九组两级调用十一() {
        ReducePlan plan = reduce(6, 2).plan(9);
        assertEquals(List.of(9, 2), plan.levelGroupCounts());
        assertEquals(11, plan.totalSummaryCalls());
    }

 
    @Test
    void reducePlan_层级不可容纳_事前抛() {
        MultiLevelReducePlanner r = reduce(6, 2);
        com.sixbean.testpike.common.exception.ServiceException ex =
                assertThrows(com.sixbean.testpike.common.exception.ServiceException.class,
                        () -> r.plan(40));
        assertTrue(ex.getMessage().contains("无法在 2 级内收敛")
                        || ex.getMessage().contains("层级上限"),
                "人话消息缺失: " + ex.getMessage());
        ReducePlan ok = reduce(6, 3).plan(40);
        assertEquals(3, ok.levels());
        assertEquals(List.of(40, 7, 2), ok.levelGroupCounts());
        assertEquals(49, ok.totalSummaryCalls());
    }

    
    @Test
    void reducePlan_归并桶均分保持顺序() {
        List<List<Integer>> buckets = reduce(6, 2).bucketize(List.of(1, 2, 3, 4, 5, 6, 7));
        assertEquals(2, buckets.size());
        assertEquals(List.of(1, 2, 3, 4), buckets.get(0));
        assertEquals(List.of(5, 6, 7), buckets.get(1));
        assertEquals(List.of(), reduce(6, 2).bucketize(List.of()));
    }

    
    @Test
    void reducePlan_组数非法抛() {
        assertThrows(IllegalArgumentException.class, () -> reduce(6, 2).plan(0));
        assertThrows(IllegalArgumentException.class, () -> reduce(6, 2).plan(-3));
    }

    
    @Test
    void tokensToChars_单一来源() {
        assertEquals(9, ContextBudgetPlanner.tokensToChars(10));
        assertEquals(480, ContextBudgetPlanner.tokensToChars(512));
    }
}
