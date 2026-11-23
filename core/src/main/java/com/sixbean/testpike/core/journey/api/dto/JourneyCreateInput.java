package com.sixbean.testpike.core.journey.api.dto;

/**
 * Creation payload of a cross-project journey under one program .
 * The owner is the single account holding exit sign-off authority over every run of
 * this journey; segments are appended separately so the form-style editor can save
 * the plan before any segment exists.
 *
 * @param programId owning program (project set) row id, must reference an active program
 * @param name journey name, unique inside the program
 * @param ownerUserId journey owner user id (sole sign-off authority)
 */
public record JourneyCreateInput(long programId, String name, long ownerUserId) {
}
