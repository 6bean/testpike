package com.sixbean.testpike.admin.skill.api.dto;

import java.util.List;


public record ProjectSkillView(
        List<ProjectSkillItem> list,
        long version) {
}
