package com.sixbean.testpike.aiservice.llm.domain.component;

/**
 *
 * @param skillId tp_skill.id
 */
public record SkillUsageTrace(long skillId, int version, int stage, int dropped) {
}
