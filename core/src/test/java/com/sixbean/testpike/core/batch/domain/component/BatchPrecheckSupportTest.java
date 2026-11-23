package com.sixbean.testpike.core.batch.domain.component;

import com.sixbean.testpike.core.plans.api.dto.SkipItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


class BatchPrecheckSupportTest {

    @Test
    void test_flaky_isolated_case_skipped_first_regardless_of_status() {
        List<SkipItem> skips = BatchPrecheckSupport.classify(
                List.of(new BatchPrecheckRow(1L, "C-1", "不稳定用例", 3, 1)),
                "skip").skips();
        assertEquals(1, skips.size());
        assertEquals("已隔离（不稳定）", skips.get(0).reason());
    }

    @Test
    void test_unevaluated_case_skipped_with_default_reason() {
        List<SkipItem> skips = BatchPrecheckSupport.classify(
                List.of(new BatchPrecheckRow(2L, "C-2", "待评审用例", 1, 0)),
                "skip").skips();
        assertEquals(1, skips.size());
        assertEquals("状态不符", skips.get(0).reason());
    }

    @Test
    void test_block_mode_rejects_only_unevaluated_and_flaky_still_skips() {
        BatchPrecheckResult blocked = BatchPrecheckSupport.classify(
                List.of(new BatchPrecheckRow(1L, "C-1", "待评审", 1, 0),
                        new BatchPrecheckRow(2L, "C-2", "隔离", 3, 1)),
                "block");
        assertTrue(blocked.blockedByUnevaluated());
        assertEquals(1, blocked.flakySkips().size());

        BatchPrecheckResult onlyFlaky = BatchPrecheckSupport.classify(
                List.of(new BatchPrecheckRow(2L, "C-2", "隔离", 3, 1)), "block");
        assertEquals(false, onlyFlaky.blockedByUnevaluated(), "flaky 隔离=跳过动作，block 模式不阻止");
    }

    @Test
    void test_approved_case_enters_execution() {
        BatchPrecheckResult ok = BatchPrecheckSupport.classify(
                List.of(new BatchPrecheckRow(1L, "C-1", "已通过", 3, 0)), "skip");
        assertEquals(List.of(1L), ok.approvedCaseIds());
        assertTrue(ok.skips().isEmpty());
    }

    @Test
    void test_skip_phrase_human_message() {
        List<SkipItem> items = List.of(new SkipItem(1L, "C-1", "甲", 1, "状态不符"),
                new SkipItem(2L, "C-2", "乙", 2, "状态不符"));
        String phrase = BatchPrecheckSupport.skipPhrase(items, "未通过评审用例");
        assertTrue(phrase.contains("跳过 2 条未通过评审用例"));
        assertTrue(phrase.contains("「甲」「乙」"));
        assertEquals("", BatchPrecheckSupport.skipPhrase(List.of(), "未通过评审用例"));
    }
}
