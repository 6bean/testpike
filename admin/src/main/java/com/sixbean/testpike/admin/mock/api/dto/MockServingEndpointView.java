package com.sixbean.testpike.admin.mock.api.dto;

import java.time.LocalDateTime;

/**
 * Serving-inventory row (admin bridge; field-for-field mirror of the core
 * MockServingEndpointView record). URL parts arrive raw and stay raw — the
 * page composes the clickable URL in one function, so a host change can
 * never leave a second stale copy in a transit layer (same posture as
 * MockServingUrlView).
 *
 * @param stubId      rule row id (edit/delete door anchor)
 * @param spaceId     owning space row id
 * @param spaceName   owning space display name
 * @param spaceKey    routing handle the URL is composed from
 * @param spaceStatus owning space lifecycle (0 draft / 1 published / 2 retired)
 * @param basePathMode prefix | strict
 * @param ruleName    rule name
 * @param priority    within-space evaluation order
 * @param method      HTTP verb (uppercase, ANY = wildcard)
 * @param pathMatchType exact | prefix | regex | template
 * @param pathPattern path body under the space route
 * @param matchersJson matcher rows (JSON array text)
 * @param responseJson response builder payload (JSON object text)
 * @param scenarioName stateful scenario membership (full rule payload rides)
 * @param requiredState scenario state this rule fires on
 * @param newState     scenario state this rule advances to
 * @param source      provenance class
 * @param sourceRef   provenance anchor
 * @param enabled     serving switch
 * @param version     optimistic lock echo for the edit door
 * @param updatedAt   last mutation time
 * @param hits7d      matched serves of this rule in the last 7 days
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
