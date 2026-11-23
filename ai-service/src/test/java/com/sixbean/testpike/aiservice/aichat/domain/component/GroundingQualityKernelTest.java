package com.sixbean.testpike.aiservice.aichat.domain.component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Material quality grading of the grounding kernel :
 * the grounded side grows from one flat state to four — strong relevance,
 * weak relevance, cross-source contradiction and suspected-stale — decided
 * by pure rules over the same candidate pool, with honest naming lines the
 * answer side must relay to the user. Zero-hit stays the untouched honest
 * shape (existing suites re-assert the old contract).
 */
class GroundingQualityKernelTest {

    private static final int MAX_CITATIONS = 5;
    private static final int EXCERPT_CHARS = 120;
    private static final double MIN_SCORE = 0.05;
    private static final double STRONG_SCORE = 0.35;
    private static final int STALE_WINDOW_DAYS = 7;

    private static final OffsetDateTime NOW =
            OffsetDateTime.of(2026, 11, 6, 9, 0, 0, 0, ZoneOffset.UTC);

    private static GroundingHit knowledge(long refId, String title, String content,
                                          double baseScore, OffsetDateTime updatedAt) {
        return new GroundingHit(GroundingKernel.KIND_KNOWLEDGE, refId, null,
                title, content, baseScore, null, false, updatedAt, null);
    }

    private static GroundingOutcome grade(String question, List<GroundingHit> candidates) {
        return GroundingKernel.assemble(question, candidates, MAX_CITATIONS,
                EXCERPT_CHARS, MIN_SCORE, true, STRONG_SCORE, STALE_WINDOW_DAYS, NOW);
    }

    @Test
    void strongEvidenceGradesHighRelevanceWithoutWarningLines() {
        var outcome = grade("回滚窗口阈值是多少", List.of(
                knowledge(1L, "回滚窗口说明", "回滚窗口阈值：默认按发布批次计算。", 0.6, NOW.minusDays(1))));

        assertFalse(outcome.zeroHit());
        assertEquals(GroundingQuality.HIGH, outcome.quality());
        assertTrue(outcome.warningLines().isEmpty());
    }

    @Test
    void evidenceAboveFloorButBelowStrongGradesWeakRelevance() {
        // base score clears the min floor yet stays under the strong bar and
        // shares no term with the question, so only the retrieval side counts
        var outcome = grade("配额分桶规则", List.of(
                knowledge(2L, "无关标题", "unrelated body text", 0.2, NOW.minusDays(1))));

        assertFalse(outcome.zeroHit());
        assertEquals(GroundingQuality.WEAK, outcome.quality());
    }

    @Test
    void sameFieldDifferentValuesAcrossSourcesGradesConflictAndNamesTheField() {
        var outcome = grade("回滚阈值配置", List.of(
                knowledge(3L, "回滚手册A", "回滚阈值：5", 0.6, NOW.minusDays(1)),
                knowledge(4L, "回滚手册B", "回滚阈值：9", 0.55, NOW.minusDays(1))));

        assertEquals(GroundingQuality.CONFLICT, outcome.quality());
        assertTrue(outcome.warningLines().stream().anyMatch(l -> l.contains("回滚阈值")),
                "conflict line must name the field the sources disagree on");
        assertTrue(outcome.warningLines().stream().anyMatch(l -> l.contains("5") && l.contains("9")),
                "conflict line must show both mutually exclusive values");
    }

    @Test
    void sameValueAcrossSourcesIsAgreementNotConflict() {
        var outcome = grade("回滚阈值配置", List.of(
                knowledge(3L, "回滚手册A", "回滚阈值：5", 0.6, NOW.minusDays(1)),
                knowledge(4L, "回滚手册B", "回滚阈值：5", 0.55, NOW.minusDays(1))));

        assertEquals(GroundingQuality.HIGH, outcome.quality());
        assertTrue(outcome.warningLines().isEmpty());
    }

    @Test
    void singleSourceRepeatedValueNeverGradesConflict() {
        var outcome = grade("回滚阈值配置", List.of(
                knowledge(3L, "回滚手册A", "回滚阈值：5\n回滚阈值：9", 0.6, NOW.minusDays(1))));

        // same source row: the kernel dedupes by row and a row contradicting
        // itself is content noise the user can read directly, not a
        // cross-source verdict — must not fake a conflict grade
        assertFalse(outcome.quality() == GroundingQuality.CONFLICT);
    }

    @Test
    void updatedBeforeStaleWindowGradesSuspectedStaleAndNamesMaterial() {
        var outcome = grade("回滚窗口阈值是多少", List.of(
                knowledge(5L, "回滚窗口说明", "回滚窗口阈值按发布批次计算。", 0.6, NOW.minusDays(8))));

        assertEquals(GroundingQuality.STALE_SUSPECT, outcome.quality());
        assertTrue(outcome.warningLines().stream().anyMatch(l -> l.contains("回滚窗口说明")),
                "stale line must name the material the user should double-check");
    }

    @Test
    void updatedInsideStaleWindowIsNotSuspectedStale() {
        var outcome = grade("回滚窗口阈值是多少", List.of(
                knowledge(5L, "回滚窗口说明", "回滚窗口阈值按发布批次计算。", 0.6, NOW.minusDays(6))));

        assertEquals(GroundingQuality.HIGH, outcome.quality());
    }

    @Test
    void hitWithoutTimestampIsNeverSuspectedStale() {
        var outcome = grade("回滚窗口阈值是多少", List.of(
                knowledge(6L, "回滚窗口说明", "回滚窗口阈值按发布批次计算。", 0.6, null)));

        assertEquals(GroundingQuality.HIGH, outcome.quality());
    }

    @Test
    void conflictOutranksStaleButStaleLineStillRides() {
        var outcome = grade("回滚阈值配置", List.of(
                knowledge(7L, "回滚手册A", "回滚阈值：5", 0.6, NOW.minusDays(30)),
                knowledge(8L, "回滚手册B", "回滚阈值：9", 0.55, NOW.minusDays(1))));

        assertEquals(GroundingQuality.CONFLICT, outcome.quality(),
                "contradiction is the sharper truth claim and wins the grade");
        assertTrue(outcome.warningLines().stream().anyMatch(l -> l.contains("回滚手册A")),
                "the stale source must still be named while the conflict leads");
    }

    @Test
    void zeroHitPoolGradesNoneAndKeepsTheHonestShape() {
        var outcome = grade("回滚阈值配置", List.of());

        assertTrue(outcome.zeroHit());
        assertEquals(GroundingQuality.NONE, outcome.quality());
        assertTrue(outcome.warningLines().isEmpty());
        assertEquals("", outcome.contextBlock());
    }

    @Test
    void contextBlockCarriesTheWarningLinesSoTheModelSeesThem() {
        var outcome = grade("回滚阈值配置", List.of(
                knowledge(3L, "回滚手册A", "回滚阈值：5", 0.6, NOW.minusDays(1)),
                knowledge(4L, "回滚手册B", "回滚阈值：9", 0.55, NOW.minusDays(1))));

        for (String line : outcome.warningLines()) {
            assertTrue(outcome.contextBlock().contains(line),
                    "every naming line must reach the prompt-side block verbatim");
        }
    }

    @Test
    void legacyOverloadKeepsVerdictShapeAndGradesWithoutTimestampRules() {
        var outcome = GroundingKernel.assemble("回滚窗口阈值是多少",
                List.of(knowledge(9L, "回滚窗口说明", "回滚窗口阈值按发布批次计算。", 0.6, null)),
                MAX_CITATIONS, EXCERPT_CHARS, MIN_SCORE, true);

        assertFalse(outcome.zeroHit());
        assertEquals(1, outcome.citations().size());
        assertTrue(outcome.warningLines().isEmpty(),
                "a versionless row cannot be accused of staleness by the legacy channel");
    }
}
