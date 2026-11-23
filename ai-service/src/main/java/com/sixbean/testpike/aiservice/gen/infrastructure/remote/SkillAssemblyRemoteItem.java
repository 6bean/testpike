package com.sixbean.testpike.aiservice.gen.infrastructure.remote;

/**
 *
 * @param skillId tp_skill.id
 * @param kind source discriminator (0 = tp_skill, 1 = tp_ai_ctx_memory
 * project memory) — mirrors core {@code SkillAssemblyItem}
 */
public record SkillAssemblyRemoteItem(
        long skillId,
        String name,
        int role,
        int priority,
        int enabled,
        int version,
        String body,
        int kind) {
}
