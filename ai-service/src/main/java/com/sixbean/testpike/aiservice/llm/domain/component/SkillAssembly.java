package com.sixbean.testpike.aiservice.llm.domain.component;

import java.util.List;


public record SkillAssembly(
        String segment,
        int estTokens,
        int injectedCount,
        int droppedCount,
        String warnMessage,
        List<SkillUsageTrace> usageTraces,
        List<Long> memoryHitIds) {
}
