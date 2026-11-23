package com.sixbean.testpike.admin.mock.api.dto;

/**
 * One drafted stub item read back from an AI drafting job's bundle (admin
 * mirror of the core view, . Field names stay identical to
 * core's MockAiJobDraftItemView — the confirmation drawer ticks these straight
 * into MockStubInput, so a rename on one side silently drops model output.
 *
 * @param name drafted rule name
 * @param method HTTP method
 * @param path stub path pattern
 * @param pathMatchType exact | prefix | regex | template
 * @param responseJson response-builder document as JSON text
 * @param scenarioName stateful scenario name when drafted, else null
 * @param faultJson fault document as JSON text when drafted, else null
 * @param sourceAnchor verbatim excerpt of the requirement the item came from
 */
public record MockAiJobDraftItemView(
        String name,
        String method,
        String path,
        String pathMatchType,
        String responseJson,
        String scenarioName,
        String faultJson,
        String sourceAnchor) {
}
