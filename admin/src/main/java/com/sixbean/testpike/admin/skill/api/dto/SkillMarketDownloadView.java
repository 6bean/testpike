package com.sixbean.testpike.admin.skill.api.dto;


public record SkillMarketDownloadView(
        Long skillId,
        String format,
        String fileName,
        String objectKey,
        String url) {
}
