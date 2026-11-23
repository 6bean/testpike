package com.sixbean.testpike.aiservice.llm.domain.component;

/**
 *
 * @param skillId tp_skill.id
 * @param kind source discriminator ({@link #KIND_SKILL}/{@link #KIND_MEMORY},
 * AP batch memory rows share the SKILL assembly
 * and budget machinery while keeping their usage accounting apart)
 */
public record SkillInjectionItem(
        long skillId,
        int version,
        int role,
        int priority,
        int enabled,
        String name,
        String body,
        int kind) {

    /** Assembly source discriminator: tp_skill convention rows. */
    public static final int KIND_SKILL = 0;
    /** Assembly source discriminator: tp_ai_ctx_memory distilled project memory. */
    public static final int KIND_MEMORY = 1;

    /** Legacy 7-arg form (S5 call sites default to SKILL kind; memory rows must state it). */
    public SkillInjectionItem(long skillId, int version, int role, int priority,
                              int enabled, String name, String body) {
        this(skillId, version, role, priority, enabled, name, body, KIND_SKILL);
    }
}
