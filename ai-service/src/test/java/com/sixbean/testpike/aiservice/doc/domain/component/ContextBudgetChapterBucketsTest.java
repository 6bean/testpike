package com.sixbean.testpike.aiservice.doc.domain.component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * chapter-bucket contract for the final extraction call. The plane
 * chapter transition (coincidence rate 100% for chapters that fit under the
 * ceiling), the legacy budget-only shape stays byte-reproducible with the
 * hot key off, and a single chapter busting the ceiling splits mid-chapter
 * but never welds the next chapter in. Shares the greedy loop with the
 * grouping plane (ContextBudgetPlanner.plan, single source, R40).
 */
class ContextBudgetChapterBucketsTest {

    private static Map<String, Object> chunk(int seq, String chapter, int charCount) {
        Map<String, Object> m = new HashMap<>();
        m.put("seq", seq);
        m.put("chapter", chapter);
        m.put("content", "字".repeat(charCount));
        return m;
    }

    private static List<Integer> seqs(List<Map<String, Object>> bucket) {
        return bucket.stream().map(c -> (Integer) c.get("seq")).toList();
    }

    /** Chapter transitions seal buckets even with budget headroom left. */
    @Test
    void alignedFaceSealsAtEveryChapterChange() {
        List<Map<String, Object>> chunks = List.of(
                chunk(1, "A", 10), chunk(2, "B", 10), chunk(3, "A", 10));
        List<List<Map<String, Object>>> buckets =
                new ContextBudgetPlanner(2000).withChapterAligned(true)
                        .planChapterBucketsByChars(60, chunks);
        assertEquals(List.of(List.of(1), List.of(2), List.of(3)),
                buckets.stream().map(ContextBudgetChapterBucketsTest::seqs).toList(),
                "「章节桶」 must never weld chapters that each fit the ceiling");
    }

    /** Coincidence rate: bucket boundaries == chapter boundaries (100%). */
    @Test
    void boundaryCoincidenceAgainstChapterTransitionsIsFull() {
        List<Map<String, Object>> chunks = List.of(
                chunk(1, "A", 10), chunk(2, "A", 10),
                chunk(3, "B", 10),
                chunk(4, "C", 10), chunk(5, "C", 10), chunk(6, "C", 10));
        // Pure-shape assertion: overlap tail off (0 is inside the accepted
        // range) so bucket heads are the native chapter starts themselves.
        List<List<Map<String, Object>>> buckets =
                new ContextBudgetPlanner(2000).withChapterAligned(true)
                        .withOverlapRatio(0)
                        .planChapterBucketsByChars(200, chunks);
        int transitions = 0;
        for (int i = 1; i < chunks.size(); i++) {
            if (!chunks.get(i - 1).get("chapter").equals(chunks.get(i).get("chapter"))) {
                transitions++;
            }
        }
        // Chapter changes sit at indexes 2 and 3 (0-based boundaries {2,3}).
        List<Integer> starts = buckets.stream().map(b -> (Integer) b.get(0).get("seq")).toList();
        assertEquals(List.of(1, 3, 4), starts, "bucket heads land on chapter starts");
        assertEquals(transitions + 1, buckets.size(),
                "every chapter transition is a bucket boundary (coincidence = 100%)");
    }

    /** Hot key off = legacy budget-only shape, block-for-block identical. */
    @Test
    void offFaceIsByteIdenticalToLegacyBudgetOnlyBuckets() {
        List<Map<String, Object>> chunks = List.of(
                chunk(1, "A", 10), chunk(2, "B", 10), chunk(3, "A", 10));
        List<List<Map<String, Object>>> legacy =
                new ContextBudgetPlanner(2000).planBucketsByChars(60, chunks);
        List<List<Map<String, Object>>> reverted =
                new ContextBudgetPlanner(2000).withChapterAligned(false)
                        .planChapterBucketsByChars(60, chunks);
        assertEquals(legacy.stream().map(ContextBudgetChapterBucketsTest::seqs).toList(),
                reverted.stream().map(ContextBudgetChapterBucketsTest::seqs).toList(),
                "关态=旧贪心形制字节可复现（T583 开关口径延伸到桶平面）");
    }

    /** A single oversized chapter splits INSIDE itself, never welding the neighbour in. */
    @Test
    void oversizedChapterSplitsInsideItsOwnBoundary() {
        List<Map<String, Object>> chunks = List.of(
                chunk(1, "BIG", 50), chunk(2, "BIG", 50), chunk(3, "NEXT", 50));
        List<List<Map<String, Object>>> buckets =
                new ContextBudgetPlanner(2000).withChapterAligned(true)
                        .planChapterBucketsByChars(60, chunks);
        assertEquals(List.of(List.of(1), List.of(2), List.of(3)),
                buckets.stream().map(ContextBudgetChapterBucketsTest::seqs).toList(),
                "budget seal inside one chapter is allowed; welding NEXT into BIG is not");
    }

    /** Token face carries the same chapter predicate (shared greedy loop). */
    @Test
    void tokenFaceSealsAtChapterChangeToo() {
        List<Map<String, Object>> chunks = List.of(
                chunk(1, "A", 100), chunk(2, "A", 100), chunk(3, "B", 100));
        List<List<Map<String, Object>>> buckets =
                new ContextBudgetPlanner(2000).withChapterAligned(true)
                        .planChapterBucketsByTokens(300, chunks);
        assertEquals(List.of(List.of(1, 2), List.of(3)),
                buckets.stream().map(ContextBudgetChapterBucketsTest::seqs).toList());
    }
}
