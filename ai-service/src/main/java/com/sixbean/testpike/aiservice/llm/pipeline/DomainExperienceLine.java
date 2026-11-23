package com.sixbean.testpike.aiservice.llm.pipeline;

import java.time.LocalDate;

/**
 * One rendered experience row for the L0 domain-experience section
 * . Value object only — the read door (DomainExperienceReader)
 * owns the queries, this line carries what the renderer needs: the text,
 * the measured evidence count behind its confidence and the date the
 * evidence was last refreshed.
 *
 * <p>Kind vocabulary is shared with the assembly view (SkillInjectionItem):
 * {@link #KIND_SKILL} = SKILL usage-ledger row, {@link #KIND_MEMORY} =
 * distilled project memory (tp_ai_ctx_memory) — so a caller filtering the
 * core assembly response needs no translation table.
 *
 * @param kind {@link #KIND_SKILL} or {@link #KIND_MEMORY}
 * @param text distilled convention text (memory) or SKILL name (usage)
 * @param evidenceCount hit_count (memory) or usage count (SKILL ledger)
 * @param freshness date the row was last refreshed / last used
 */
public record DomainExperienceLine(int kind, String text, long evidenceCount,
                                   LocalDate freshness) {

    /** Assembly vocabulary: tp_skill usage-ledger row. */
    public static final int KIND_SKILL = 0;
    /** Assembly vocabulary: tp_ai_ctx_memory distilled project memory. */
    public static final int KIND_MEMORY = 1;
}
