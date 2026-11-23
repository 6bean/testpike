package com.sixbean.testpike.core.skill.api.dto;


public record SkillMarketQuery(
        int page,
        int size,
        String keyword,
        Integer type,
        Long tagId,
        String sortBy,
        String sortDir) {
}
