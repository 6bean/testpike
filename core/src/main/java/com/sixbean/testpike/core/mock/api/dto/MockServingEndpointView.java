package com.sixbean.testpike.core.mock.api.dto;

import java.time.LocalDateTime;

/**
 * One callable mock endpoint across every space of a project (serving
 * inventory, the cross-space aggregation the per-space doc page cannot show).
 * <p>An endpoint identity is (spaceKey, method, pathPattern): the same path
 * under two spaces is two externally different URLs, so a flat per-project
 * list is only honest when the space handle travels on every row.
 * <p>URL fields are raw parts, never a composed string — the same posture as
 * MockServingUrlView: composing is one function's job on the consumer side,
 * so a host change can never leave a second stale copy behind.
 *
 * @param stubId        rule row id (edit/delete door anchors on it)
 * @param spaceId       owning space row id
 * @param spaceName     owning space display name
 * @param spaceKey      routing handle the URL is composed from
 * @param spaceStatus   owning space lifecycle (0 draft / 1 published / 2 retired)
 * @param basePathMode  prefix | strict (how deep a path still matches)
 * @param ruleName      rule name
 * @param priority      within-space evaluation order
 * @param method        HTTP verb (uppercase, ANY = wildcard)
 * @param pathMatchType exact | prefix | regex | template
 * @param pathPattern   path body under the space route
 * @param matchersJson  matcher rows (JSON array text)
 * @param responseJson  response builder payload (JSON object text)
 * @param scenarioName  stateful scenario membership (null = stateless) — the
 *                      inventory row carries the full rule payload so the
 *                      shared editor can echo every mutable column
 * @param requiredState scenario state this rule fires on
 * @param newState      scenario state this rule advances to
 * @param source        provenance class
 * @param sourceRef     provenance anchor
 * @param enabled       serving switch (a disabled rule is listed but never answers)
 * @param version       optimistic lock echo for the edit door
 * @param updatedAt     last mutation time
 * @param hits7d        matched-serve count of this rule in the last 7 days
 *                      (honesty field: 0 on a published rule = nobody is
 *                      calling it; draft spaces truthfully carry 0 too)
 */
public record MockServingEndpointView(
        Long stubId,
        Long spaceId,
        String spaceName,
        String spaceKey,
        Integer spaceStatus,
        String basePathMode,
        String ruleName,
        Integer priority,
        String method,
        String pathMatchType,
        String pathPattern,
        String matchersJson,
        String responseJson,
        String scenarioName,
        String requiredState,
        String newState,
        String source,
        String sourceRef,
        Boolean enabled,
        Integer version,
        LocalDateTime updatedAt,
        Long hits7d) {
}
