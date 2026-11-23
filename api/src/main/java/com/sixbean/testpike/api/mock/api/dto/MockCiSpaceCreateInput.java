package com.sixbean.testpike.api.mock.api.dto;

/**
 * Request body of POST /open/mock/ci/spaces CI door).
 * <p>Deliberately narrow: a pipeline asks for a throwaway space, not for the
 * full management-plane space form. Anything richer belongs to the admin UI
 * bridge, so this door exposes exactly the three knobs CI needs.
 *
 * @param name display name (optional — defaults to spaceKey)
 * @param spaceKey global routing handle, required and checked for shape here
 * before the bridge (core re-checks as the authority)
 * @param ttlHours optional lifetime; core stamps expires_at and the scheduled
 * sweep retires the space (null = never expires)
 */
public record MockCiSpaceCreateInput(
        String name,
        String spaceKey,
        Integer ttlHours) {
}
