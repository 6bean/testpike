package com.sixbean.testpike.core.skill.api.dto;

import java.time.LocalDateTime;

/**
 *
 * @param id SKILL ID
 */
public record SkillRecycleInfo(Long id, Long projectId, String name, Integer type, int status,
                               int currentVersion, int version, Long deletedBy,
                               String deletedByName, LocalDateTime deletedAt) {
}
