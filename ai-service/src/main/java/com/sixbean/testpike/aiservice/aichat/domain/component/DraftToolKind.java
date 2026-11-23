package com.sixbean.testpike.aiservice.aichat.domain.component;

/**
 * Draft-card routing fact for one dialog write tool (tool-surface clearing
 * batch stage 2, emitter side).
 * <p>The emitter used to match tool names as literals, which meant every new
 * write domain needed a code change in the hot orchestrator — a tool that was
 * registered but unknown to the name list became a dead tool (refused by the
 * dispatcher, no card emitted). Routing now comes from the registry: core
 * answers which published+enabled write tools carry a draft objectType, and
 * this record is the wire mirror of that answer.
 *
 * @param toolName tp_tool.name (the FC call name found in the transcript)
 * @param objectType draft kind token (matches core DraftObjectType.wireName);
 * blank/absent = the tool has no draft card by design
 */
public record DraftToolKind(String toolName, String objectType) {

    public boolean hasDraftKind() {
        return objectType != null && !objectType.isBlank();
    }
}
