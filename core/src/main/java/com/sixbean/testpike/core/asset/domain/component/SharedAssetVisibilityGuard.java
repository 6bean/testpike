package com.sixbean.testpike.core.asset.domain.component;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.springframework.stereotype.Component;

/**
 * Ownership fence and discoverability rules for the shared asset chain (, single
 * source). Both sides of the publish model hinge here: owner-only writes and
 * globally-readable published heads. The admin bridge and the frontend must consume
 * these semantics instead of mirroring them (R40 — no second implementation).
 */
@Component
public class SharedAssetVisibilityGuard {

    /** Published state marker (tp_asset.status). */
    public static final int STATUS_PUBLISHED = 1;
    /** Chain-level withdrawal marker: the chain leaves global discovery. */
    public static final int STATUS_WITHDRAWN = 2;

    /**
     * Every write on an asset chain (publish / re-publish / withdraw) belongs to the
     * owner project only. A rejected cross-project write names the owner project so
     * the caller learns where to request the asset instead of guessing.
     *
     * @throws ServiceException 403 FORBIDDEN when caller and owner projects differ
     */
    public void requireOwner(long callerProjectId, long ownerProjectId, String ownerProjectName) {
        if (callerProjectId == ownerProjectId) {
            return;
        }
        throw new ServiceException("This asset belongs to project \"" + ownerProjectName
                + "\"; only that project can publish or withdraw it", BizErrorCode.FORBIDDEN);
    }

    /**
     * Global discoverability: only the head version of a published chain is listed
     * across projects. Superseded versions (kept for pinned references) and withdrawn
     * chains leave the shared discovery surface.
     */
    public boolean discoverable(int status, boolean head) {
        return status == STATUS_PUBLISHED && head;
    }
}
