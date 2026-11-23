package com.sixbean.testpike.admin.skill.api.dto;

import java.time.LocalDateTime;

/**
 *
 * @param id SKILL ID
 */
public record SkillRecycleView(Long id, Long projectId, String name, Integer type, int status,
                               int currentVersion, int version, Long deletedBy,
                               String deletedByName, LocalDateTime deletedAt) {
}
