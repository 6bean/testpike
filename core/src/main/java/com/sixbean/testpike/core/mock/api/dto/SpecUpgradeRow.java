package com.sixbean.testpike.core.mock.api.dto;

/**
 * One named existing-rule row of the contract-upgrade plan (changed or
 * removed state): the wizard shows which exact rule moves, not a bare id,
 * so the row carries the rule identity (verb + path) beside its id.
 * Pure projection of {@link MockStubInfo} fields — no new facts.
 *
 * @param stubId      rule row id (target of the update / stale-mark action)
 * @param name        rule name as stored
 * @param method      HTTP verb of the rule
 * @param pathPattern path under the space route
 */
public record SpecUpgradeRow(
        long stubId,
        String name,
        String method,
        String pathPattern) {

    /** Project a stored rule onto one upgrade-plan row. */
    public static SpecUpgradeRow from(MockStubInfo stub) {
        return new SpecUpgradeRow(stub.id(), stub.name(), stub.method(), stub.pathPattern());
    }
}
