package com.sixbean.testpike.core.skill.api.dto;

import java.util.List;

/**
 *
 * @param version revision
 */
public record ProjectSkillView(
        List<ProjectSkillItem> list,
        long version) {
}
