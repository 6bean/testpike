package com.sixbean.testpike.aiservice.gen.infrastructure.remote;

/**
 *
 * @param skillId tp_skill.id
 */
public record SkillUsageRemoteInput(
        Long taskId,
        Long skillId,
        Integer version,
        Integer stage,
        Integer dropped) {
}
