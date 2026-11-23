package com.sixbean.testpike.core.apiaccess;

/**
 * Declared payload of the fixture's create door. The pass-through landing composes a body only from
 * these fields, so the catalogue has to read them off the type.
 *
 * @param projectId owning project (re-stamped by the landing door, never trusted from a proposal)
 * @param name      tag name
 * @param type      optional tag type
 */
public record TagPayloadFixture(Long projectId, String name, Integer type) {
}
