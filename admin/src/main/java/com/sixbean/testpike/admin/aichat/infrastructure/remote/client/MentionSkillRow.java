package com.sixbean.testpike.admin.aichat.infrastructure.remote.client;

/**
 * Contract mirror of the core SKILL assembly row (S5;
 * GET /internal/skills/project/{pid}/assembly) narrowed to the fields the
 * @-mention palette needs — the JSON arrives whole, Jackson keeps what the
 * record declares. Field names align with core {@code SkillAssemblyItem}.
 *
 * @param skillId tp_skill.id
 * @param name    skill name (the palette label source)
 */
public record MentionSkillRow(
        long skillId,
        String name,
        int role,
        int priority,
        int enabled,
        int version,
        String body) {
}
