package com.sixbean.testpike.aiservice.doc.domain.component;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EE batch — grouping granularity decoupled from the model window.
 * Why this exists: the group budget used to be the full effective window, so a
 * larger catalog-row limit produced FEWER, fatter nodes and the final extract
 * call read only a fraction of the document (live incident: 121,375 tokens in,
 * 4,924 tokens read, 4.06% kept). The node caps below are factory defaults from
 * ai.doc.group-max-tokens AND ai.doc.group-max-chunks, and the per-request
 * output estimate stays within ai.doc.bucket-output-max-tokens — a single
 * monolithic send is allowed only when BOTH constraints hold.
 */
class ContextBudgetGroupGranularityTest {

    /** Chunks sized in whole tokens through the planner's char≈token face. */
    private static List<Map<String, Object>> chunks(int count, int charsPerChunk) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            out.add(Map.of("content", "字".repeat(charsPerChunk), "seq", i + 1));
        }
        return out;
    }

    @Test
    void groupBudgetIsTheMinOfWindowAndNodeCap() {
        ContextBudgetPlanner planner = new ContextBudgetPlanner(598_771);
        // Window alone (legacy face) would weld the static budget back in; the
        // factory node cap of 8,000 tokens must win inside a huge window.
        assertEquals(ContextBudgetPlanner.DEFAULT_GROUP_MAX_TOKENS,
                planner.groupBudgetTokens(), "factory node cap must bite below a huge window");
        ContextBudgetPlanner smallWindow = new ContextBudgetPlanner(3_000)
                .withEffectiveBudget(3_000);
        assertEquals(3_000, smallWindow.groupBudgetTokens(),
                "a window smaller than the node cap keeps the window (min face)");
    }

    @Test
    void compressionTriggersBeyondNodeCapEvenInsideTheWindow() {
        // Char face: 512 tokens ≈ 480 chars, so 121,375 tokens ≈ 116,520 chars.
        ContextBudgetPlanner planner = new ContextBudgetPlanner(598_771);
        List<Map<String, Object>> big = chunks(240, 480); // 240 x ~512 tokens
        assertTrue(planner.needsCompression(big),
                "a document past the node cap may never be sent monolithically just because the window is huge");
        assertTrue(planner.needsCompressionByTokens(big));
        List<Map<String, Object>> small = chunks(8, 480);
        assertFalse(planner.needsCompression(small), "within the node cap the single send stays");
    }

    @Test
    void groupingHonoursTokenAndChunkCapsTogether() {
        ContextBudgetPlanner planner = new ContextBudgetPlanner(598_771);
        List<Map<String, Object>> big = chunks(240, 480);
        List<List<Map<String, Object>>> groups = planner.planGroups(big);
        // 240 chunks x ~512 tokens ≈ 122,880 tokens over an 8,000-token face:
        // the node count must jump from the legacy 1-2 to calibration scale.
        assertTrue(groups.size() >= 16,
                "8639-shaped input must split into >=16 nodes, was " + groups.size());
        for (List<Map<String, Object>> group : groups) {
            assertTrue(group.size() <= ContextBudgetPlanner.DEFAULT_GROUP_MAX_CHUNKS,
                    "a node may not exceed ai.doc.group-max-chunks, was " + group.size());
            long tokens = group.stream().mapToLong(c -> ContextBudgetPlanner.contentOf(c).length() * 512L / 480L).sum();
            assertTrue(tokens <= ContextBudgetPlanner.DEFAULT_GROUP_MAX_TOKENS * 1.25,
                    "node overshoots its token ceiling beyond the overlap tail, tokens=" + tokens);
        }
    }

    @Test
    void readerFailClosesToFactoryAndWriteSideSharesTheWindow() {
        // Read side: null/blank/garbage/out-of-window all answer the factory
        // value; the caller names the key (mirrors the overlap-ratio discipline).
        assertEquals(ContextBudgetPlanner.DEFAULT_GROUP_MAX_TOKENS,
                ContextBudgetPlanner.groupMaxTokensOf("abc"));
        assertEquals(ContextBudgetPlanner.DEFAULT_GROUP_MAX_TOKENS,
                ContextBudgetPlanner.groupMaxTokensOf("-5"));
        assertEquals(ContextBudgetPlanner.DEFAULT_GROUP_MAX_TOKENS,
                ContextBudgetPlanner.groupMaxTokensOf(""));
        assertEquals(9_000, ContextBudgetPlanner.groupMaxTokensOf("9000"));
        // Write side of the same key accepts exactly the same window.
        assertTrue(ContextBudgetPlanner.acceptsGroupMaxTokens("8000"));
        assertFalse(ContextBudgetPlanner.acceptsGroupMaxTokens("200000000"));
        assertFalse(ContextBudgetPlanner.acceptsGroupMaxTokens("junk"));
        assertFalse(ContextBudgetPlanner.acceptsGroupMaxTokens(null));

        assertEquals(ContextBudgetPlanner.DEFAULT_GROUP_MAX_CHUNKS,
                ContextBudgetPlanner.groupMaxChunksOf("junk"));
        assertTrue(ContextBudgetPlanner.acceptsGroupMaxChunks("45"));
        assertFalse(ContextBudgetPlanner.acceptsGroupMaxChunks("0"));
        assertFalse(ContextBudgetPlanner.acceptsGroupMaxChunks("501"));

        assertEquals(ContextBudgetPlanner.DEFAULT_BUCKET_OUTPUT_MAX_TOKENS,
                ContextBudgetPlanner.bucketOutputMaxTokensOf("-1"));
        assertTrue(ContextBudgetPlanner.acceptsBucketOutputMaxTokens("24000"));
        assertFalse(ContextBudgetPlanner.acceptsBucketOutputMaxTokens("99"));

        assertEquals(ContextBudgetPlanner.DEFAULT_OUTPUT_AMPLIFICATION,
                ContextBudgetPlanner.outputAmplificationOf("nope"), 0.0001);
        assertEquals(2.5, ContextBudgetPlanner.outputAmplificationOf("2.5"), 0.0001);
        assertFalse(ContextBudgetPlanner.acceptsOutputAmplification("11"));
        assertTrue(ContextBudgetPlanner.acceptsOutputAmplification("2.94"));
    }

    @Test
    void outputEstimateCapsTheFinalBucketCeiling() {
        // Both constraints decide a single send: even a generous stored input
        // ceiling is capped back to outputMax / amplification (24,000 / 2.94
        // ≈ 8,163 tokens), so one request can never be predicted past the
        // provider output wall.
        ContextBudgetPlanner planner = new ContextBudgetPlanner(598_771)
                .withEffectiveBudget(598_771);
        int capped = planner.capByOutputCeiling(100_000);
        int expected = (int) (ContextBudgetPlanner.DEFAULT_BUCKET_OUTPUT_MAX_TOKENS
                / ContextBudgetPlanner.DEFAULT_OUTPUT_AMPLIFICATION);
        assertEquals(expected, capped);
        // A stored ceiling already below the output-derived face passes through.
        assertEquals(6_000, planner.capByOutputCeiling(6_000));
        // 0 = bucketing disabled stays 0 (legacy monolithic call byte-identical).
        assertEquals(0, planner.capByOutputCeiling(0));
    }

    @Test
    void storedLimitsFlowIntoGroupingViaValueObjectAssembly() {
        // withGroupCeilings returns a NEW planner (thread-safe value face), the
        // original keeps factory behaviour.
        ContextBudgetPlanner base = new ContextBudgetPlanner(598_771);
        ContextBudgetPlanner tuned = base.withGroupCeilings(4_000, 10, 24_000, 2.94);
        assertEquals(ContextBudgetPlanner.DEFAULT_GROUP_MAX_TOKENS, base.groupBudgetTokens());
        assertEquals(4_000, tuned.groupBudgetTokens());
        List<List<Map<String, Object>>> groups = tuned.planGroups(chunks(60, 480));
        for (List<Map<String, Object>> group : groups) {
            assertTrue(group.size() <= 10, "stored chunk cap must hold, was " + group.size());
        }
    }

    // ── residual face: chapter-span cap on the grouping plane ────────
    // Golden-window defect red-A (recall 0.772): two groups welded 35+ chapters
    // each and the model truncated the group tail (missing chapters 86-120 and
    // 246-280). Neither the token cap (8,000) nor the chunk cap (45) bounds how
    // MANY chapters ride in one node — the enumeration span needs its own cap.

    /** One chunk per chapter, each far below every other cap (the golden face:
     *  460 tiny chapters, the 400+ chapter document that produced the spans). */
    private static List<Map<String, Object>> chapteredChunks(int count, int charsPerChunk) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            out.add(Map.of("content", "x".repeat(charsPerChunk), "seq", i + 1,
                    "chapter", "ch-" + (i + 1)));
        }
        return out;
    }

    private static int distinctChapters(List<Map<String, Object>> group) {
        Set<String> chapters = new HashSet<>();
        for (Map<String, Object> chunk : group) {
            chapters.add(ContextBudgetPlanner.chapterOf(chunk));
        }
        return chapters.size();
    }

    @Test
    void chapterSpanCapSealsGroupsBeforeTheWeldedTailFace() {
        ContextBudgetPlanner planner = new ContextBudgetPlanner(598_771)
                .withChapterAligned(true)
                .withGroupMaxChapters(20);
        List<List<Map<String, Object>>> groups = planner.planGroupsByTokens(chapteredChunks(60, 50));
        // Without the cap the same face welds [45,15]; with cap 20 it must seal 3x20.
        assertEquals(3, groups.size(), "60 chapters at cap 20 => 3 groups");
        for (List<Map<String, Object>> group : groups) {
            assertTrue(distinctChapters(group) <= 20,
                    "chapter span cap breached: " + distinctChapters(group) + " chapters in one group");
            assertEquals(group.size(), distinctChapters(group), "coverage must stay exact (no overlap here)");
        }
    }

    @Test
    void chapterSpanCapIsInertOnLegacyFacesForByteIdentity() {
        // Default face (no cap assembled) keeps the pre-cap shape byte-identical.
        ContextBudgetPlanner uncapped = new ContextBudgetPlanner(598_771).withChapterAligned(true);
        List<List<Map<String, Object>>> legacy = uncapped.planGroupsByTokens(chapteredChunks(60, 50));
        assertEquals(2, legacy.size(), "chunk cap 45 alone seals [45,15] as before");
        assertEquals(45, distinctChapters(legacy.get(0)));
        // Aligned plane off + cap stored = cap must not fire either (legacy greedy untouched).
        ContextBudgetPlanner legacyGreedy = new ContextBudgetPlanner(598_771)
                .withGroupMaxChapters(20);
        List<List<Map<String, Object>>> sameShape =
                legacyGreedy.planGroupsByTokens(chapteredChunks(60, 50));
        assertEquals(legacy, sameShape, "cap must be inert while the chapter face is off");
    }

    @Test
    void chapterSpanCapNeverSplitsInsideASingleChapterAndKeepsCoverage() {
        // Three chapters; chapter 2 alone busts the token budget and must keep
        // splitting by budget only — a span cap must never drop or duplicate.
        List<Map<String, Object>> mixed = new ArrayList<>();
        mixed.add(Map.of("content", "x".repeat(50), "seq", 1, "chapter", "ch-1"));
        mixed.add(Map.of("content", "x".repeat(10_000), "seq", 2, "chapter", "ch-2"));
        mixed.add(Map.of("content", "x".repeat(50), "seq", 3, "chapter", "ch-3"));
        ContextBudgetPlanner planner = new ContextBudgetPlanner(598_771)
                .withChapterAligned(true)
                .withGroupMaxChapters(2);
        List<List<Map<String, Object>>> groups = planner.planGroupsByTokens(mixed);
        int total = groups.stream().mapToInt(List::size).sum();
        assertEquals(3, total, "every chunk present exactly once");
        assertTrue(groups.stream().anyMatch(g -> g.size() == 1
                        && "ch-2".equals(ContextBudgetPlanner.chapterOf(g.get(0)))),
                "the oversized chapter keeps its own group");
    }

    @Test
    void chapterCapReaderAndWriterShareOneIntervalFace() {
        // Read side fails closed to the factory; write side accepts the same window.
        assertEquals(ContextBudgetPlanner.DEFAULT_GROUP_MAX_CHAPTERS,
                ContextBudgetPlanner.groupMaxChaptersOf(null));
        assertEquals(ContextBudgetPlanner.DEFAULT_GROUP_MAX_CHAPTERS,
                ContextBudgetPlanner.groupMaxChaptersOf("garbage"));
        assertEquals(ContextBudgetPlanner.DEFAULT_GROUP_MAX_CHAPTERS,
                ContextBudgetPlanner.groupMaxChaptersOf("0"));
        assertEquals(ContextBudgetPlanner.DEFAULT_GROUP_MAX_CHAPTERS,
                ContextBudgetPlanner.groupMaxChaptersOf("1001"));
        assertEquals(12, ContextBudgetPlanner.groupMaxChaptersOf("12"));
        assertTrue(ContextBudgetPlanner.acceptsGroupMaxChapters("12"));
        assertFalse(ContextBudgetPlanner.acceptsGroupMaxChapters("0"));
        assertFalse(ContextBudgetPlanner.acceptsGroupMaxChapters("1001"));
        assertFalse(ContextBudgetPlanner.acceptsGroupMaxChapters(" "));
    }
}
