package com.sixbean.testpike.admin.skill.api.dto;

import java.util.List;


public record ProjectSkillBindingRequest(
        List<ProjectSkillBindingItem> skills,
        Long expectedVersion) {
}
