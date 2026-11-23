package com.sixbean.testpike.core.document.domain.component;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


class DocChunkDiffTest {

    private final DocChunkDiff diff = new DocChunkDiff();

    private static Map<String, String> map(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void test_add_modify_delete_three_sets() {
        List<ChapterDelta> deltas = diff.diff(
                map("A", "正文1", "B", "正文2", "C", "正文3"),
                map("A", "正文1改", "C", "正文3", "D", "正文4"));
        assertEquals(List.of(new ChapterDelta("A", DocChunkDiff.MODIFY),
                new ChapterDelta("D", DocChunkDiff.ADD),
                new ChapterDelta("B", DocChunkDiff.DELETE)), deltas);
    }

    @Test
    void test_whitespace_only_change_not_modify() {
        List<ChapterDelta> deltas = diff.diff(map("A", "正文1  "), map("A", " 正文1\n"));
        assertTrue(deltas.isEmpty(), "trim 后同内容不得报修改");
    }

    @Test
    void test_same_input_stable_order() {
        Map<String, String> oldCh = map("A", "1", "B", "2");
        Map<String, String> newCh = map("B", "2x", "C", "3");
        List<ChapterDelta> first = diff.diff(oldCh, newCh);
        List<ChapterDelta> second = diff.diff(oldCh, newCh);
        assertEquals(first, second, "同输入必同输出（顺序稳定可断言）");
    }
}
