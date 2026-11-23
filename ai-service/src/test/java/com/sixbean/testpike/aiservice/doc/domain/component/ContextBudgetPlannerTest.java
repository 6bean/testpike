package com.sixbean.testpike.aiservice.doc.domain.component;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


class ContextBudgetPlannerTest {

    private static Map<String, Object> chunk(int seq, int contentChars) {
        return Map.of("seq", seq, "chapter", "章节" + seq,
                "content", "字".repeat(contentChars));
    }

    
    @Test
    void budgetCharsOf_缺失与非法值回退缺省() {
        int fallback = ContextBudgetPlanner.DEFAULT_TOKEN_BUDGET * 480 / 512;
        assertEquals(fallback, ContextBudgetPlanner.budgetCharsOf(null));
        assertEquals(fallback, ContextBudgetPlanner.budgetCharsOf(""));
        assertEquals(fallback, ContextBudgetPlanner.budgetCharsOf("abc"));
        assertEquals(fallback, ContextBudgetPlanner.budgetCharsOf("0"));
        assertEquals(fallback, ContextBudgetPlanner.budgetCharsOf("-3"));
    }

    
    @Test
    void budgetCharsOf_合法值按480比512换算() {
        assertEquals(9, ContextBudgetPlanner.budgetCharsOf("10"));
        assertEquals(480, ContextBudgetPlanner.budgetCharsOf("512"));
    }

    
    @Test
    void needsCompression_总字数与预算比较含边界() {
        ContextBudgetPlanner planner = new ContextBudgetPlanner(512);
        assertFalse(planner.needsCompression(List.of(chunk(1, 300), chunk(2, 180))));
        assertTrue(planner.needsCompression(List.of(chunk(1, 300), chunk(2, 181))));
    }

    
    @Test
    void planGroups_贪心封组覆盖不重不漏() {
        ContextBudgetPlanner planner = new ContextBudgetPlanner(512);
        List<Map<String, Object>> chunks = IntStream.rangeClosed(1, 5)
                .mapToObj(i -> chunk(i, 100)).toList();
        List<List<Map<String, Object>>> groups = planner.planGroups(chunks);
        assertEquals(List.of(4, 1), groups.stream().map(List::size).toList());
        assertEquals(List.of(1, 2, 3, 4, 5),
                groups.stream().flatMap(List::stream)
                        .map(c -> ((Number) c.get("seq")).intValue())
                        .collect(Collectors.toList()));
    }

    
    @Test
    void planGroups_单块超预算独占一组不丢弃() {
        ContextBudgetPlanner planner = new ContextBudgetPlanner(512);
        List<Map<String, Object>> chunks = List.of(chunk(1, 600), chunk(2, 10));
        List<List<Map<String, Object>>> groups = planner.planGroups(chunks);
        assertEquals(2, groups.size());
        assertEquals(1, groups.get(0).size());
        assertEquals(600, String.valueOf(groups.get(0).get(0).get("content")).length());
        assertEquals(1, groups.get(1).size());
    }

    // ── Group-overlap window: adjacent groups share boundary content so a
    // requirement point cut across the seam stays visible in both groups.
    // The overlap is charged into the next group's budget (no over-issuing).

    /** Seam chunk sealed at the tail of group 1 must re-appear at the head of group 2. */
    @Test
    void planGroups_重叠窗口_切缝块在相邻两组均可见() {
        ContextBudgetPlanner planner = new ContextBudgetPlanner(512) // 480 chars
                .withOverlapRatio(0.10);                              // overlap budget 48 chars
        List<Map<String, Object>> chunks = List.of(
                chunk(1, 200), chunk(2, 200), chunk(3, 40), chunk(4, 240));
        List<List<Map<String, Object>>> groups = planner.planGroups(chunks);
        assertEquals(2, groups.size());
        assertEquals(3, ((Number) groups.get(0).get(groups.get(0).size() - 1)
                .get("seq")).intValue());
        assertEquals(3, ((Number) groups.get(1).get(0).get("seq")).intValue());
        int groupChars = groups.get(1).stream()
                .mapToInt(c -> String.valueOf(c.get("content")).length()).sum();
        assertTrue(groupChars <= 480, "重叠必须计入组预算，不得超发：实际 " + groupChars);
    }

    /** ratio=0 is byte-identical to the legacy grouping (zero-drift comparison). */
    @Test
    void planGroups_重叠比例为零与旧行为逐组相等() {
        List<Map<String, Object>> chunks = IntStream.rangeClosed(1, 9)
                .mapToObj(i -> chunk(i, i % 3 == 0 ? 40 : 110)).toList();
        List<List<Map<String, Object>>> legacy = new ContextBudgetPlanner(512).planGroups(chunks);
        List<List<Map<String, Object>>> zero = new ContextBudgetPlanner(512)
                .withOverlapRatio(0.0).planGroups(chunks);
        assertEquals(legacy, zero);
    }

    /** Token plane shares the same overlap function: tail chunk (43 tokens) lands in both groups. */
    @Test
    void planGroupsByTokens_重叠尾计入组预算() {
        ContextBudgetPlanner planner = new ContextBudgetPlanner(512)
                .withOverlapRatio(0.10); // overlap budget 51 tokens
        List<Map<String, Object>> chunks = List.of(
                chunk(1, 180), chunk(2, 180), chunk(3, 40), chunk(4, 200));
        List<List<Map<String, Object>>> groups = planner.planGroupsByTokens(chunks);
        assertEquals(2, groups.size());
        assertEquals(3, ((Number) groups.get(0).get(groups.get(0).size() - 1)
                .get("seq")).intValue());
        assertEquals(3, ((Number) groups.get(1).get(0).get("seq")).intValue());
    }

    /** Config face: null/garbage/out-of-window values fail closed to the factory 0.10. */
    @Test
    void overlapRatioOf_非法与越界回退出厂值() {
        assertEquals(0.10, ContextBudgetPlanner.overlapRatioOf(null));
        assertEquals(0.10, ContextBudgetPlanner.overlapRatioOf(""));
        assertEquals(0.10, ContextBudgetPlanner.overlapRatioOf("abc"));
        assertEquals(0.10, ContextBudgetPlanner.overlapRatioOf("-0.1"));
        assertEquals(0.10, ContextBudgetPlanner.overlapRatioOf("0.26"));
        assertEquals(0.0, ContextBudgetPlanner.overlapRatioOf("0"));
        assertEquals(0.25, ContextBudgetPlanner.overlapRatioOf("0.25"));
        assertEquals(0.2, ContextBudgetPlanner.overlapRatioOf("0.2"));
        assertFalse(ContextBudgetPlanner.acceptsOverlapRatio("0.5"));
        assertTrue(ContextBudgetPlanner.acceptsOverlapRatio("0.10"));
    }
}
