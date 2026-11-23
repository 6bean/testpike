package com.sixbean.testpike.core.skill.api.dto;

/**
 *
 * @param skillId tp_skill.id
 * @param kind source discriminator (0 = tp_skill convention,
 * 1 = tp_ai_ctx_memory distilled project memory, AP batch
 * single-channel reuse; for memory rows skillId
 * IS the memory entry id — never traced into tp_skill_usage,
 * its accounting face is hit_count)
 */
public record SkillAssemblyItem(
        long skillId,
        String name,
        int role,
        int priority,
        int enabled,
        int version,
        String body,
        int kind) {
}
