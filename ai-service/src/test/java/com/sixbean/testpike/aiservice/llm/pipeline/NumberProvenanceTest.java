package com.sixbean.testpike.aiservice.llm.pipeline;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L2 fixtures for the no-mental-math rule :
 * at least three positive and three negative cases, and every exemption form
 * must have its own hit case — an exemption that never fires is a silent
 * vacuous rule, the same anti-pattern the gate self-tests defend against.
 */
class NumberProvenanceTest {

    @Test
    void detectsQuantityShapedFigures() {
        List<String> found = NumberProvenance.findQuantityMentions(
                "共 128 条用例，通过率 92.5%，平均耗时 45 秒。");
        assertEquals(3, found.size(), "条数/占比/时长 three quantity families must all be caught: " + found);
        assertTrue(found.get(0).contains("128"));
        assertTrue(found.get(1).contains("92.5"));
        assertTrue(found.get(2).contains("45"));
    }

    @Test
    void exemptsOrdinalAndStepNumbers() {
        // Exemption head-note families: ordinals and step numbers never read
        // as measured amounts, or every step-by-step answer would be flagged.
        List<String> found = NumberProvenance.findQuantityMentions(
                "第 3 步执行失败；步骤 2 已通过；1、需求点整理。");
        assertTrue(found.isEmpty(), "ordinals/steps must stay exempt: " + found);
    }

    @Test
    void exemptsYears() {
        List<String> found = NumberProvenance.findQuantityMentions(
                "该缺陷登记于 2026 年，回溯至 1999 年的初始版本。");
        assertTrue(found.isEmpty(), "year forms must stay exempt: " + found);
    }

    @Test
    void unsourcedNumbersAreNamedWhenToolEvidenceLacksThem() {
        String answer = "共 128 条用例，通过率 92.5%。";
        List<String> unsourced = NumberProvenance.unsourcedQuantities(
                answer, List.of("查询返回：用例总数 128 条"));
        assertEquals(1, unsourced.size(), "only the figure absent from tool output is suspect: " + unsourced);
        assertTrue(unsourced.get(0).contains("92.5"));
    }

    @Test
    void allFiguresSourcedYieldsNoFinding() {
        String answer = "共 128 条用例，失败 7 条。";
        List<String> unsourced = NumberProvenance.unsourcedQuantities(
                answer, List.of("用例总数 128 条", "失败数 7 条"));
        assertTrue(unsourced.isEmpty(), "figures present in tool returns are provenance-backed");
    }

    @Test
    void emptyOrBlankEvidenceStillNamesEveryQuantity() {
        List<String> unsourced = NumberProvenance.unsourcedQuantities(
                "耗时 3 分钟，成本 50 元。", List.of());
        assertEquals(2, unsourced.size(), "no evidence text cannot source anything: " + unsourced);
    }

    @Test
    void bareNumberWithoutUnitIsNotAQuantityClaim() {
        assertFalse(NumberProvenance.findQuantityMentions("version 3 已发布").stream()
                .findAny().isPresent(), "a bare figure without a unit word is out of the rule's shape");
    }
}
