package com.sixbean.testpike.aiservice.llm.pipeline;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain-experience section renderer :
 * ① injected — every line carries its confidence and freshness annotation;
 * ② absent — no experience rows means no section at all (zero noise);
 * ③ over-cap — more rendered lines than the hot K8 cap keeps the newest head
 * and honestly discloses the cut tail instead of silently dropping it.
 * Pure renderer contract: rows arrive already project-scoped (the read door
 * owns the bucket filter; this piece owns the shape of the section).
 */
class DomainExperienceSectionTest {

    private static DomainExperienceLine memory(String text, long hits, String day) {
        return new DomainExperienceLine(DomainExperienceLine.KIND_MEMORY, text, hits,
                LocalDate.parse(day));
    }

    private static DomainExperienceLine skillUsage(String name, long uses, String day) {
        return new DomainExperienceLine(DomainExperienceLine.KIND_SKILL, name, uses,
                LocalDate.parse(day));
    }

    @Test
    void injectedLinesCarryConfidenceAndFreshnessAnnotations() {
        String section = DomainExperienceSection.render(List.of(
                memory("接口用例的断言必须校验业务码字段", 5, "2026-11-01"),
                skillUsage("登录流检查", 12, "2026-11-05")), 20);

        assertTrue(section.startsWith("\n\n"), "the section lands as its own block");
        assertTrue(section.contains("—— 项目经验"), "section header names the block");
        assertTrue(section.contains("接口用例的断言必须校验业务码字段"));
        assertTrue(section.contains("置信：历史装配命中 5 次"), "memory rows show hit ledger");
        assertTrue(section.contains("时效：2026-11-01"), "memory rows show their freshness");
        assertTrue(section.contains("SKILL《登录流检查》"), "skill rows render as skill usage");
        assertTrue(section.contains("置信：近 30 天调用 12 次"), "skill rows show the usage ledger");
        assertTrue(section.contains("时效：最后使用 2026-11-05"));
    }

    @Test
    void noExperienceMeansTheSectionIsAbsentWithZeroNoise() {
        assertEquals("", DomainExperienceSection.render(List.of(), 20));
        assertEquals("", DomainExperienceSection.render(null, 20));
        assertEquals("", DomainExperienceSection.render(
                List.of(memory("任何行", 1, "2026-11-01")), 0),
                "a non-positive cap cannot emit a section");
    }

    @Test
    void overCapKeepsTheNewestHeadAndDisclosesTheCutTail() {
        List<DomainExperienceLine> lines = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            lines.add(memory("经验第" + i + "条", i, "2026-11-01"));
        }
        String section = DomainExperienceSection.render(lines, 20);

        long bullets = section.lines().filter(l -> l.startsWith("- ")).count();
        assertEquals(20, bullets, "exactly the cap of lines is injected");
        assertTrue(section.contains("经验第0条"), "the head is kept");
        assertFalse(section.contains("经验第29条"), "the stale tail is cut");
        assertTrue(section.contains("另有 10 条"), "the cut is honestly disclosed");
    }
}
