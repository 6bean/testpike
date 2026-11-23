package com.sixbean.testpike.core.requirement.api.dto;


public record RequirementCreateInput(Long projectId, Integer source, String sourceId,
                                     String title, String acceptanceCriteria, String chapter,
                                     Boolean dedupOverride, RequirementSourceAnchor sourceAnchor) {

    /** Legacy seven-arity call sites keep compiling with no evidence anchor. */
    public RequirementCreateInput(Long projectId, Integer source, String sourceId,
                                  String title, String acceptanceCriteria, String chapter,
                                  Boolean dedupOverride) {
        this(projectId, source, sourceId, title, acceptanceCriteria, chapter, dedupOverride, null);
    }

    /** Legacy six-arity call sites keep compiling with the dedup gate honoured. */
    public RequirementCreateInput(Long projectId, Integer source, String sourceId,
                                  String title, String acceptanceCriteria, String chapter) {
        this(projectId, source, sourceId, title, acceptanceCriteria, chapter, null, null);
    }
}
