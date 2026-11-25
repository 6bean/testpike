package com.sixbean.testpike.admin.mock.api.dto;

/**
 * Admin mirror of core's named upgrade-plan row (changed or removed rule):
 * id plus the rule identity the wizard shows on the line. Field-for-field
 * forwarding — the bridge re-decides nothing.
 *
 * @param stubId      rule row id
 * @param name        rule name as stored
 * @param method      HTTP verb of the rule
 * @param pathPattern path under the space route
 */
public record MockSpecUpgradeRowView(
        long stubId,
        String name,
        String method,
        String pathPattern) {
}
