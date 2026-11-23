package com.sixbean.testpike.core.webelement.api.dto;

/**
 * Conversion request: turn ONE booked network failure of one risk row into
 * a pending case draft. The three coordinates address a fact the probe
 * already recorded — nothing here carries risk judgement, bounds rules or
 * an anchor claim; those live behind the single service door.
 */
public record CaseDraftFromFailureInput(
        Long projectId,
        long profileId,
        int failureIndex) {
}
