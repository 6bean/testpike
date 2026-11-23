package com.sixbean.testpike.core.skill.api.dto;

import java.util.List;


public record ProjectSkillBindingInput(
        List<ProjectSkillBindingItem> skills,
        Long expectedVersion) {
}
