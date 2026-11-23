package com.sixbean.testpike.core.spec.domain.service;

import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.spec.api.dto.SpecSubscriptionInfo;
import com.sixbean.testpike.core.spec.api.dto.SpecSubscriptionSaveRequest;
import com.sixbean.testpike.core.spec.api.dto.SubscriptionScanResult;

/**
 * Contract subscription service (scheduled pull channel: scan + due check + change notice).
 * <p>Every pull goes through the ApiSpecService upload core single source (parse/SHA256
 * idempotence/version-chain diff semantics stay zero-copy — no second source). On success
 * with a non-empty diff the scan produces a pending preview plus project-owner in-site
 * notices (SPEC_CHANGE_DETECTED, DB dedup). create/update validation lives here as the
 * single source; nothing is re-checked on the admin bridge.
 */
public interface SpecSubscriptionService {

    /**
 * Create a subscription (external URL source or set-internal provider source;
 * the subscription name is the contract name written into the version chain and
 * must be unique inside the consumer project).
 *
 * @throws com.sixbean.testpike.common.exception.ServiceException
 * blank name / non http(s) url source / invalid interval / program source
 * missing provider pair / provider sharing no active program with the
 * consumer / provider holding no such spec = 400 human-readable;
 * duplicate subscription name = 409 human-readable
 */
    SpecSubscriptionInfo create(long operatorId, SpecSubscriptionSaveRequest request);

    /** URL-source convenience adapter (all validation lives in the DTO form above). */
    default SpecSubscriptionInfo create(long operatorId, long projectId, String name,
                                        String url, Integer checkIntervalMinutes) {
        return create(operatorId, new SpecSubscriptionSaveRequest(projectId, name, url,
                checkIntervalMinutes, null, null, null, null));
    }

    
    SpecSubscriptionInfo get(long id);

    /**
     * Edit a subscription: blank name/url/interval/enabled keep current values; blank
     * sourceKind keeps the current kind. Switching the source (kind, url, or the
     * provider pair) resets the due window so the next scan runs immediately.
     *
     * @throws com.sixbean.testpike.common.exception.ServiceException
     *         validation same as create (400); rename colliding with another
     *         subscription in the project = 409; missing subscription = 404
     */
    SpecSubscriptionInfo update(long operatorId, long id, SpecSubscriptionSaveRequest request);

    /** URL-source convenience adapter (all validation lives in the DTO form above). */
    default SpecSubscriptionInfo update(long operatorId, long id, String name, String url,
                                        Integer checkIntervalMinutes, Boolean enabled) {
        return update(operatorId, id, new SpecSubscriptionSaveRequest(null, name, url,
                checkIntervalMinutes, enabled, null, null, null));
    }

    
    void delete(long operatorId, long id);

    
    PageResult<SpecSubscriptionInfo> list(long projectId, int page, int size);

    
    SubscriptionScanResult scanDue();
}
