package com.sixbean.testpike.core.skill.api.dto;

import java.util.List;


public record SkillMarketItem(
        Long id,
        Long projectId,
        String name,
        int type,
        String typeLabel,
        String statusLabel,
        int currentVersion,
        String authorName,
        int downloadCount,
        int refProjectCount,
        int call30dCount,
        List<String> tags,
        String bodyPreview,
        String createdAt) {
}
