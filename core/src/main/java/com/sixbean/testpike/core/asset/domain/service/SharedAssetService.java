package com.sixbean.testpike.core.asset.domain.service;

import com.sixbean.testpike.core.asset.api.dto.AssetPublishRequest;
import com.sixbean.testpike.core.asset.api.dto.SharedAssetInfo;
import com.sixbean.testpike.core.common.clientobject.PageResult;

/**
 * Shared asset publish chain : publish, cross-project discover, withdraw.
 * <p>Single-table version chain over tp_asset (V358), shaped after the api spec chain
 * (V109) so no second version mechanism appears. Re-publishing the same
 * (project, kind, name) appends asset_version+1 and links prev_asset_id — the arrival
 * of a new version row IS the update fact; superseded rows stay readable because
 * references pin concrete row ids. Ownership fence and discoverability rules live in
 * {@link com.sixbean.testpike.core.asset.domain.component.SharedAssetVisibilityGuard}
 * as the single source; the admin bridge never re-checks them.
 * <p>Out of this segment by design: reference registration and change notices live in
 * the sibling service and the shared change notifier; this interface never re-checks
 * what the notifier already reads from the same SQL single source.
 */
public interface SharedAssetService {

    /**
     * Publish (or re-publish) an asset version into the caller's own project.
     *
     * @param operatorId      acting user recorded on the row
     * @param callerProjectId project the write is issued from; must equal
     *                        request.projectId()
     * @throws com.sixbean.testpike.common.exception.ServiceException
     *         blank name/payload or kind outside the four-value set = 400
     *         VALIDATION_ERROR (checked before touching the DB so no poisoned
     *         transaction can spill into the same unit); cross-project write =
     *         403 FORBIDDEN naming the owner project
     */
    SharedAssetInfo publish(long operatorId, long callerProjectId, AssetPublishRequest request);

    /** Read one version row by id (also superseded/withdrawn ones — pinned references stay resolvable). */
    SharedAssetInfo get(long assetId);

    /**
     * Globally discoverable listing = published heads of every project, optional kind
     * filter (blank = all kinds), newest chain touched first. Superseded versions and
     * withdrawn chains never appear here.
     */
    PageResult<SharedAssetInfo> listDiscoverable(String kind, int page, int size);

    /**
     * Same listing with server-side sorting: {@code sortBy} must be a key of the
     * implementation's sort whitelist (blank = historical updated_at DESC order);
     * an off-list key is refused fail-closed with a 400 naming the allowed columns.
     */
    PageResult<SharedAssetInfo> listDiscoverable(String kind, int page, int size,
                                                 String sortBy, String sortDir);

    /**
 * Resolve the head row id of one (project, kind, name) chain — SQL single source,
 * consumed by reference pinning and by the update notice fan-out.
 *
 * @throws com.sixbean.testpike.common.exception.ServiceException no such chain = 404
 */
    long resolveHeadId(long projectId, String kind, String name);

    /**
     * Withdraw the whole chain (every version row flips to status 2). Owner project only.
     *
     * @throws com.sixbean.testpike.common.exception.ServiceException
     *         missing row = 404; cross-project write = 403 naming the owner project
     */
    void withdraw(long operatorId, long callerProjectId, long assetId, boolean force);
}
