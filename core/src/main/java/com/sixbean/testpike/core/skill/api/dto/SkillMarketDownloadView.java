package com.sixbean.testpike.core.skill.api.dto;

/**
 *
 * @param skillId SKILL id
 * @param format md / zip
 */
public record SkillMarketDownloadView(
        Long skillId,
        String format,
        String fileName,
        String objectKey,
        String url) {
}
