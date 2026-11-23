package com.sixbean.testpike.core.asset.domain.component;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ownership fence and cross-project discoverability rules of the shared asset chain:
 * owner writes pass, cross-project writes are rejected naming the owner project, and
 * only published heads stay globally discoverable.
 */
class SharedAssetVisibilityGuardTest {

    private final SharedAssetVisibilityGuard guard = new SharedAssetVisibilityGuard();

    @Test
    void ownerWritePasses() {
        guard.requireOwner(7L, 7L, "Provider Project");
    }

    @Test
    void crossProjectWriteIsRejectedAndNamesOwnerProject() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> guard.requireOwner(8L, 7L, "Provider Project"));
        assertEquals(BizErrorCode.FORBIDDEN, ex.getCode());
        assertTrue(ex.getMessage().contains("Provider Project"),
                "rejection must point at the owner project, was: " + ex.getMessage());
    }

    @Test
    void publishedHeadIsDiscoverableGlobally() {
        assertTrue(guard.discoverable(SharedAssetVisibilityGuard.STATUS_PUBLISHED, true));
    }

    @Test
    void supersededVersionLeavesGlobalDiscovery() {
        assertFalse(guard.discoverable(SharedAssetVisibilityGuard.STATUS_PUBLISHED, false));
    }

    @Test
    void withdrawnChainLeavesGlobalDiscovery() {
        assertFalse(guard.discoverable(SharedAssetVisibilityGuard.STATUS_WITHDRAWN, true));
    }
}
