package com.sixbean.testpike.aiservice.doc.domain.component;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * : chapter-aligned grouping. The greedy loop stays the single source
 * (no second implementation) and gains an optional chapter-boundary face;
 * with the flag off every plane must answer byte-identical to the legacy
 * budget-only greedy. The collapsed node label of the summarize plane gains
 * an aligned face that lists each distinct chapter instead of pinning only
 * the first chunk's chapter.
 */
class ContextBudgetChapterAlignedTest {

    private static Map<String, Object> chunk(int seq, String chapter, int contentChars) {
        Map<String, Object> row = new java.util.HashMap<>();
        row.put("seq", seq);
        row.put("chapter", chapter);
        row.put("content", "字".repeat(contentChars));
        return row;
    }

    private static List<Integer> seqs(List<Map<String, Object>> group) {
        return group.stream().map(c -> (Integer) c.get("seq")).toList();
    }

    /** Flag default is OFF: existing constructions keep the legacy face. */
    @Test
    void chapterAlignedDefaultsToOff() {
        assertFalse(new ContextBudgetPlanner(1000).chapterAligned());
        ContextBudgetPlanner aligned = new ContextBudgetPlanner(1000).withChapterAligned(true);
        assertTrue(aligned.chapterAligned());
        assertFalse(new ContextBudgetPlanner(1000).chapterAligned());
    }

    /** Off face: chapters are ignored, one fat group under budget = legacy. */
    @Test
    void flagOff_ignoresChaptersLikeLegacyGreedy() {
        List<Map<String, Object>> chunks = List.of(
                chunk(1, "A", 100), chunk(2, "A", 100), chunk(3, "B", 100), chunk(4, "B", 100));
        List<List<Map<String, Object>>> groups =
                new ContextBudgetPlanner(2000).planGroups(chunks);
        assertEquals(1, groups.size(), "legacy must weld all four chunks together");
        assertEquals(List.of(1, 2, 3, 4), seqs(groups.get(0)));
    }

    /** On face (root-cause F1 fix): a chapter seam alone never seals an
     *  unfilled group — adjacent chapters keep loading into the same group
     *  until the token/chunk caps are reached (EE §nine target shape, not
     *  one group per chapter). */
    @Test
    void flagOn_smallChaptersMergeUntilCapReached() {
        List<Map<String, Object>> chunks = List.of(
                chunk(1, "A", 100), chunk(2, "A", 100), chunk(3, "A", 100),
                chunk(4, "B", 100), chunk(5, "B", 100), chunk(6, "B", 100));
        List<List<Map<String, Object>>> groups =
                new ContextBudgetPlanner(2000).withChapterAligned(true).planGroups(chunks);
        assertEquals(1, groups.size(), "chapter seams under the caps must not seal early");
        assertEquals(List.of(1, 2, 3, 4, 5, 6), seqs(groups.get(0)));
    }

    /** The 460-tiny-chapter face that burned the golden window: with the
     *  EE node ceilings (8,000 tokens / 45 chunks) and the factory overlap
     *  window, adjacent one-chunk chapters weld into groups of up to 45
     *  chunks — the ledger lands near the ruled ~16 groups, never 460. */
    @Test
    void flagOn_manyTinyChaptersKeepTheGroupLedgerNearTheRuledShape() {
        List<Map<String, Object>> chunks = new ArrayList<>();
        for (int i = 1; i <= 460; i++) {
            chunks.add(chunk(i, "chapter-" + i, 50));
        }
        List<List<Map<String, Object>>> groups =
                new ContextBudgetPlanner(20_000)
                        .withGroupCeilings(8000, 45, 24000, 2.94)
                        .withChapterAligned(true)
                        .planGroups(chunks);
        assertTrue(groups.size() <= 20 && groups.size() >= 10,
                "460 one-chunk chapters must weld to the ruled ~16-group ledger, got "
                        + groups.size());
        assertTrue(groups.stream().allMatch(g -> g.size() <= 45),
                "the node chunk cap still binds every sealed group");
        assertEquals(45, groups.get(0).size(), "the first group fills to the chunk cap");
    }

    /** Interleaved chapters produce non-interleaving groups covering every chunk. */
    @Test
    void flagOn_unionIsCompleteAndGroupOrderKept() {
        List<Map<String, Object>> chunks = List.of(
                chunk(1, "A", 10), chunk(2, "B", 10), chunk(3, "A", 10), chunk(4, "B", 10));
        List<List<Map<String, Object>>> groups =
                new ContextBudgetPlanner(2000).withChapterAligned(true).planGroups(chunks);
        List<Integer> union = new ArrayList<>();
        for (List<Map<String, Object>> group : groups) {
            union.addAll(seqs(group));
        }
        assertEquals(List.of(1, 2, 3, 4), union, "every chunk lands once, in order");
    }

    /** Alignment is optional, never forced: one oversized chapter still splits by budget. */
    @Test
    void flagOn_budgetStillBoundsWithinOneChapter() {
        List<Map<String, Object>> chunks = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            chunks.add(chunk(i, "A", 8));
        }
        List<List<Map<String, Object>>> groups =
                new ContextBudgetPlanner(20).withChapterAligned(true).planGroups(chunks);
        assertEquals(3, groups.size(), "18-char budget fits two 8-char chunks per group");
        assertEquals(List.of(1, 2), seqs(groups.get(0)));
        assertEquals(List.of(3, 4), seqs(groups.get(1)));
        assertEquals(List.of(5), seqs(groups.get(2)));
    }

    /** The node cap keeps applying inside one chapter (dual constraint stays in force). */
    @Test
    void flagOn_maxChunksCapAppliesInsideChapter() {
        List<Map<String, Object>> chunks = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            chunks.add(chunk(i, "A", 10));
        }
        List<List<Map<String, Object>>> groups =
                new ContextBudgetPlanner(2000)
                        .withGroupCeilings(8000, 2, 24000, 2.94)
                        .withChapterAligned(true)
                        .planGroups(chunks);
        assertEquals(3, groups.size());
        assertTrue(groups.stream().allMatch(g -> g.size() <= 2));
    }

    /** When the seal is cap-driven and lands exactly at a chapter seam, the
     *  overlap tail still crosses the seam (rewrite under the fill-then-seal
     *  contract: the boundary alone no longer seals, the budget bust does). */
    @Test
    void flagOn_overlapTailCrossesTheSeam() {
        List<Map<String, Object>> chunks = List.of(
                chunk(1, "A", 100), chunk(2, "A", 100), chunk(3, "A", 60),
                chunk(4, "B", 60));
        // 278 tokens -> 260 chars budget: chunks 1-3 weld to exactly 260, and
        // the first B chunk busts the cap right AT the seam, so the seal is
        // cap-driven; the 0.25 overlap window (65 chars) carries the 60-char
        // tail chunk across into the next chapter's group.
        List<List<Map<String, Object>>> groups =
                new ContextBudgetPlanner(278)
                        .withChapterAligned(true)
                        .withOverlapRatio(0.25)
                        .planGroups(chunks);
        assertEquals(2, groups.size(), "cap bust at the seam still seals");
        assertEquals(List.of(1, 2, 3), seqs(groups.get(0)));
        assertEquals(List.of(3, 4), seqs(groups.get(1)),
                "the sealed group's tail must ride into the next chapter's head");
    }

    /** Buckets plane stays byte-identical (alignment gates the grouping plane only). */
    @Test
    void flagOn_doesNotTouchBucketsPlane() {
        List<Map<String, Object>> chunks = List.of(
                chunk(1, "A", 10), chunk(2, "B", 10), chunk(3, "A", 10));
        List<List<Map<String, Object>>> groups =
                new ContextBudgetPlanner(2000).withChapterAligned(true)
                        .planBucketsByChars(60, chunks);
        assertEquals(1, groups.size(), "buckets keep the budget-only face");
    }

    /** Closed label = legacy collapse text, byte for byte. */
    @Test
    void labelOff_keepsLegacyCollapseText() {
        List<Map<String, Object>> group = List.of(chunk(1, "A", 5), chunk(2, "A", 5));
        assertEquals("A（合并 2 个分块）",
                ContextBudgetPlanner.renderGroupChapterLabel(group, false));
        assertEquals("A",
                ContextBudgetPlanner.renderGroupChapterLabel(List.of(chunk(1, "A", 5)), false));
    }

    /** Open label lists every distinct chapter of the node, order kept. */
    @Test
    void labelOn_listsAllDistinctChapters() {
        List<Map<String, Object>> group = List.of(
                chunk(1, "A", 5), chunk(2, "A", 5), chunk(3, "B", 5));
        assertEquals("A、B（合并 3 个分块）",
                ContextBudgetPlanner.renderGroupChapterLabel(group, true));
        assertEquals("A、B（合并 2 个分块）",
                ContextBudgetPlanner.renderGroupChapterLabel(
                        List.of(chunk(1, "A", 5), chunk(2, "B", 5)), true));
    }

    /** The hot key carries the ruled name and the factory-ON reading face. */
    @Test
    void keyNameIsTheRuledKey() {
        assertEquals("ai.doc.chapter-aligned-grouping.enabled",
                ContextBudgetPlanner.KEY_CHAPTER_ALIGNED_GROUPING);
    }
}
