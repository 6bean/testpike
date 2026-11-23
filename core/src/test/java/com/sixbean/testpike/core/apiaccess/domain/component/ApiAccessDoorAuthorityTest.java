package com.sixbean.testpike.core.apiaccess.domain.component;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Door-url localization at dial time: the registry stores an absolute url, and its port belongs to
 * whichever instance seeded it, not to the service that is answering now.
 *
 * <p>Found in the field: a stack running on its own port invoked {@code apiAccess.read} and got an
 * HTTP 400, because the stored authority still pointed at the main checkout's listener — the door it
 * reached belonged to a different catalog. Correcting the row instead of the dial would put two
 * stacks on one database into a last-starter-wins fight over registry state, so the row stays as
 * reviewed and each instance dials itself.
 */
class ApiAccessDoorAuthorityTest {

    @Test
    void doorRowsMoveToTheListeningPort() {
        assertEquals("http://localhost:18093/internal/api-access/read",
                ApiAccessDoorAuthority.localize("http://localhost:8083/internal/api-access/read", 18093));
        assertEquals("http://localhost:18093/internal/api-access/endpoints",
                ApiAccessDoorAuthority.localize("http://localhost:8083/internal/api-access/endpoints", 18093));
        assertEquals("http://localhost:18093/internal/api-access/mcp-tools",
                ApiAccessDoorAuthority.localize("http://localhost:8083/internal/api-access/mcp-tools", 18093));
    }

    @Test
    void anAlreadyLocalUrlDoesNotMove() {
        assertNull(ApiAccessDoorAuthority.localize(
                        "http://localhost:18093/internal/api-access/read", 18093),
                "no rewrite when nothing drifted");
    }

    @Test
    void somebodyElsesTargetIsLeftAlone() {
        assertNull(ApiAccessDoorAuthority.localize("http://inventory-service:9000/internal/api-access/read", 18093),
                "a different host is a deliberate routing decision");
        assertNull(ApiAccessDoorAuthority.localize("http://localhost:8083/internal/cases/create", 18093),
                "only this feature's doors are ours to correct");
        assertNull(ApiAccessDoorAuthority.localize("http://localhost:8083", 18093));
        assertNull(ApiAccessDoorAuthority.localize(null, 18093));
    }

    @Test
    void instanceFormKeepsTheCallerSimple() {
        ApiAccessDoorAuthority authority = new ApiAccessDoorAuthority(18093);
        assertEquals("http://localhost:18093/internal/api-access/write",
                authority.localize("http://localhost:8083/internal/api-access/write"));
        // the untouched pass-through case returns the stored value itself, not null
        assertEquals("http://localhost:8083/internal/other", authority.localize("http://localhost:8083/internal/other"));
    }
}
