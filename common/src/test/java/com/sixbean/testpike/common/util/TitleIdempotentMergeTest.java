package com.sixbean.testpike.common.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * Behavioural contract of the single-source title merge used by the
 * document extraction planes :
 * mode A = retry merge keeping confirmed rows byte-compatible with the
 * legacy core mergeByIdempotentTitle; mode B = cross-group fold where
 * the first seen row wins. Normalization equivalence classes
 * (full-width / whitespace / case) are asserted because the legacy
 * verbatim comparison was defeated by a single added space.
 */
class TitleIdempotentMergeTest {

    /** Minimal row shape: only title + a confirmation marker matter here. */
    private record Row(long id, String title, boolean confirmed) {
    }

    private static final UnaryOperator<Row> MARK_SELECTED =
            row -> new Row(row.id(), row.title(), true);

    @Test
    void normalizeTitleFoldsFullWidthWhitespaceAndCase() {
        assertEquals("login with password",
                TitleIdempotentMerge.normalizeTitle("  Login　ｗｉｔｈ  PASSWORD "));
        assertEquals(TitleIdempotentMerge.normalizeTitle("需求点１"),
                TitleIdempotentMerge.normalizeTitle("需求点1"));
        assertEquals("", TitleIdempotentMerge.normalizeTitle(null));
    }

    @Test
    void retryMergeAdoptsConfirmedRowAtTheNewSlotAcrossNormalizedVariants() {
        List<Row> previous = List.of(
                new Row(7, "需求点1", true),
                new Row(8, "未确认旧条目", false));
        List<Row> current = List.of(
                new Row(0, "需求点１ ", false));

        List<Row> merged = TitleIdempotentMerge.choosePreservingConfirmed(
                previous, current, Row::title, Row::confirmed, MARK_SELECTED);

        assertEquals(1, merged.size());
        // the confirmed row is adopted wholesale (id 7 kept), never the fresh one
        assertEquals(7L, merged.get(0).id());
        assertEquals(List.of(7L), merged.stream().map(Row::id).toList());
    }

    @Test
    void retryMergeKeepsConfirmedRowsMissingFromTheFreshRoundAtTheTail() {
        List<Row> previous = List.of(
                new Row(1, "alpha", true),
                new Row(2, "beta", true),
                new Row(3, "gamma", false));
        List<Row> current = List.of(
                new Row(0, "beta", false),
                new Row(0, "delta", false));

        List<Row> merged = TitleIdempotentMerge.choosePreservingConfirmed(
                previous, current, Row::title, Row::confirmed, MARK_SELECTED);

        // beta adopted at its fresh slot, delta is the fresh round's own row,
        // then the untouched confirmed alpha lands at the tail — exactly the
        // legacy per-position order (fresh round first, leftovers appended).
        assertEquals(List.of(2L, 4L, 1L),
                merged.stream()
                        .map(row -> row.id() == 0 ? 4L : row.id())
                        .toList());
        assertTrue(merged.get(0).confirmed());
        assertTrue(merged.get(2).confirmed());
    }

    @Test
    void retryMergeTreatsMissingFreshRoundAsConfirmedTailOnly() {
        List<Row> previous = List.of(new Row(9, "kept", true));

        List<Row> merged = TitleIdempotentMerge.choosePreservingConfirmed(
                previous, null, Row::title, Row::confirmed, MARK_SELECTED);

        assertEquals(List.of(9L), merged.stream().map(Row::id).toList());
    }

    @Test
    void retryMergeDropsDuplicateConfirmedKeysAndKeepsTheLastWritten() {
        List<Row> previous = List.of(
                new Row(1, "same title", true),
                new Row(2, "same  title", true));
        List<Row> current = List.of(new Row(0, "SAME TITLE", false));

        List<Row> merged = TitleIdempotentMerge.choosePreservingConfirmed(
                previous, current, Row::title, Row::confirmed, MARK_SELECTED);

        // LinkedHashMap.put semantics of the legacy merge: later row wins the key
        assertEquals(List.of(2L), merged.stream().map(Row::id).toList());
    }

    @Test
    void foldKeepsFirstSeenPerNormalizedTitleAndPassesKeylessRowsThrough() {
        List<Row> across = new ArrayList<>(List.of(
                new Row(1, "订单导出", false),
                new Row(2, "", false),
                new Row(3, "订单导出 ", false),
                new Row(4, "订单Ｂ导出", false),
                new Row(5, "订单b导出", false),
                new Row(6, "  ", false)));

        List<Row> folded = TitleIdempotentMerge.foldFirstSeenByTitle(
                across, Row::title);

        assertEquals(List.of(1L, 2L, 4L, 6L),
                folded.stream().map(Row::id).toList());
    }

    @Test
    void foldOnNullOrEmptyInputAnswersEmpty() {
        assertEquals(List.of(), TitleIdempotentMerge.foldFirstSeenByTitle(null, Row::title));
        assertEquals(List.of(), TitleIdempotentMerge.foldFirstSeenByTitle(List.of(), Row::title));
    }

    /** (merge-preserved markers): every row dropped by the
 * fold is routed through the hook into its survivor, so identifier
 * tokens planted in the dropped bodies stay recallable. */
    @Test
    void foldHookRoutesDuplicateRowTextIntoTheSurvivor() {
        List<Map<String, String>> rows = List.of(
                Map.of("t", "退款时限", "b", "RQ-0007：初审五个工作日"),
                Map.of("t", " 退款时限 ", "b", "RQ-0007：审核意见五个营业日"),
                Map.of("b", "无标题行"));

        List<Map<String, String>> folded = TitleIdempotentMerge.foldFirstSeenByTitle(
                rows, row -> row.get("t"),
                (survivor, duplicate) -> Map.of("t", survivor.get("t"),
                        "b", survivor.get("b") + " / " + duplicate.get("b")));

        assertEquals(2, folded.size());
        assertTrue(folded.get(0).get("b").contains("审核意见五个营业日"),
                "折叠钩子必须收到每一条被丢下的重复行: " + folded.get(0).get("b"));
        assertEquals("无标题行", folded.get(1).get("b"));
    }
}
