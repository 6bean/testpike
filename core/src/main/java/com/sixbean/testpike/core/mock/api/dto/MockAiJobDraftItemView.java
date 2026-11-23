package com.sixbean.testpike.core.mock.api.dto;

/**
 * One drafted stub item read back from the job's draft bundle ( segment
 * 4a). Field names mirror the write side exactly (MockDraftGenServiceImpl
 * bundle items): the confirmation drawer ticks these into MockStubInput on
 * confirm, so any drift here silently drops model output.
 *
 * @param name drafted rule name
 * @param method HTTP method (GET/POST/PUT/DELETE/PATCH/ANY)
 * @param path stub path pattern
 * @param pathMatchType exact | prefix | regex | template
 * @param responseJson response-builder document as JSON text
 * @param scenarioName stateful scenario name when drafted, else null
 * @param faultJson fault document (delay/disconnect) as JSON text when
 * drafted, else null — kept verbatim, never re-shaped
 * @param sourceAnchor verbatim excerpt of the requirement the item was drafted from
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
