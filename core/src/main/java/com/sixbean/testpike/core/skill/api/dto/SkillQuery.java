package com.sixbean.testpike.core.skill.api.dto;


public record SkillQuery(
        int page,
        int size,
        String keyword,
        Integer type,
        Integer status,
        Long projectId,
        Long tagId,
        String sortBy,
        String sortDir) {
}
