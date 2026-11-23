package com.sixbean.testpike.admin.journey.api.dto;

/**
 * Create-journey request (admin side of the bridge).
 * Serialized as-is toward core {@code JourneyCreateInput} — same JSON field names.
 *
 * @param programId   owning program (project set) id
 * @param name        journey display name, unique inside the program
 * @param ownerUserId journey owner; exit sign-off is reserved to this user
 */
public record CreateJourneyRequest(long programId, String name, long ownerUserId) {
}
