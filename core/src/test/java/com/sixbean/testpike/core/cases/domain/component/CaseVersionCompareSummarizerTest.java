package com.sixbean.testpike.core.cases.domain.component;

import com.sixbean.testpike.core.cases.api.dto.CaseSnapshotStep;
import com.sixbean.testpike.core.cases.api.dto.CaseVersionCompareInfo;
import com.sixbean.testpike.core.cases.api.dto.CaseVersionDiff;
import com.sixbean.testpike.core.cases.api.dto.CaseVersionFieldChange;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One-line compare-card summarizer .
 * The dialog's "compare vA and vB" answer must be a single human line
 * listing WHICH fields differ — never a JSON dump of before/after values.
 * The steps family is presented aligned by stepNo (added / removed /
 * modified counts), consuming the field changes the shared
 * CaseVersionDiffCalculator already produced (no second diff logic here).
 */
class CaseVersionCompareSummarizerTest {

    @Test
    void identicalVersionsSaysSoInOneHonestLine() {
        CaseVersionDiff diff = new CaseVersionDiff(12L, 3, 7, false, List.of());
        CaseVersionCompareInfo info = CaseVersionCompareSummarizer.summarize(diff);

        assertEquals(0, info.changedFieldCount());
        assertEquals("用例 #12 的 v3 与 v7 内容完全一致", info.summary());
    }

    @Test
    void plainFieldChangesListTheirLabelsOnOneLine() {
        CaseVersionDiff diff = new CaseVersionDiff(12L, 3, 7, false, List.of(
                new CaseVersionFieldChange("title", "测试目标", "旧标题", "新标题"),
                new CaseVersionFieldChange("priority", "优先级", 1, 3)));
        CaseVersionCompareInfo info = CaseVersionCompareSummarizer.summarize(diff);

        assertEquals(2, info.changedFieldCount());
        assertEquals("用例 #12 v3 → v7：2 处差异：测试目标、优先级", info.summary());
        // The card never echoes the changed VALUES — only which fields moved.
        assertFalse(info.summary().contains("旧标题"), "summary must not dump before/after values");
        assertFalse(info.summary().contains("null"));
    }

    @Test
    void stepFamilyAlignsByStepNoIntoAddedRemovedModifiedCounts() {
        List<CaseSnapshotStep> from = List.of(
                new CaseSnapshotStep(1, "打开登录页", 0),
                new CaseSnapshotStep(2, "输入账号密码", 0),
                new CaseSnapshotStep(3, "断言跳转首页", 1));
        List<CaseSnapshotStep> to = List.of(
                new CaseSnapshotStep(1, "打开登录页并核对标题", 0),
                new CaseSnapshotStep(2, "输入账号密码", 0),
                new CaseSnapshotStep(4, "断言存在退出按钮", 1));
        // 1 = modified (description differs), 2 = untouched, 3 = removed, 4 = added.
        CaseVersionDiff diff = new CaseVersionDiff(12L, 3, 7, false, List.of(
                new CaseVersionFieldChange("steps", "操作步骤", from, to)));
        CaseVersionCompareInfo info = CaseVersionCompareSummarizer.summarize(diff);

        assertEquals(1, info.changedFieldCount(), "the steps family counts as ONE changed field");
        assertEquals("用例 #12 v3 → v7：1 处差异：操作步骤（改1·增1·删1）", info.summary());
    }

    @Test
    void untouchedStepsOnBothSidesProduceNoCounts() {
        List<CaseSnapshotStep> same = List.of(new CaseSnapshotStep(1, "打开页面", 0));
        CaseVersionDiff diff = new CaseVersionDiff(12L, 3, 7, false, List.of(
                new CaseVersionFieldChange("steps", "操作步骤", same, same)));
        CaseVersionCompareInfo info = CaseVersionCompareSummarizer.summarize(diff);

        // The calculator only emits a steps change when the lists differ, so
        // this shape should not occur; if it ever does, the card must stay
        // honest (counts of zero) instead of fabricating a difference.
        assertEquals("用例 #12 v3 → v7：1 处差异：操作步骤（改0·增0·删0）", info.summary());
    }

    @Test
    void wideSpanAppendsTheAdjacentVersionHint() {
        CaseVersionDiff diff = new CaseVersionDiff(12L, 3, 28, true, List.of(
                new CaseVersionFieldChange("status", "评审状态", 1, 2)));
        CaseVersionCompareInfo info = CaseVersionCompareSummarizer.summarize(diff);

        assertTrue(info.summary().contains("用例 #12 v3 → v28：1 处差异：评审状态"));
        assertTrue(info.summary().contains("相隔 25 版"), "hint must name the actual span");
        assertTrue(info.summary().contains("建议对比相邻版本"));
    }

    @Test
    void mixedFieldAndStepChangesKeepOneLineOrder() {
        CaseVersionDiff diff = new CaseVersionDiff(9L, 1, 2, false, List.of(
                new CaseVersionFieldChange("title", "测试目标", "a", "b"),
                new CaseVersionFieldChange("steps", "操作步骤",
                        List.of(new CaseSnapshotStep(1, "x", 0)), List.of()),
                new CaseVersionFieldChange("script", "脚本", "s1", "s2")));
        CaseVersionCompareInfo info = CaseVersionCompareSummarizer.summarize(diff);

        assertEquals(3, info.changedFieldCount());
        assertEquals("用例 #9 v1 → v2：3 处差异：测试目标、操作步骤（改0·增0·删1）、脚本", info.summary());
        assertTrue(info.summary().indexOf('\n') < 0, "card must be a single line");
    }
}
