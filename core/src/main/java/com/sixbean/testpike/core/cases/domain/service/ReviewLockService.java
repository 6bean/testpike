package com.sixbean.testpike.core.cases.domain.service;

import com.sixbean.testpike.core.cases.api.dto.ReviewLockInfo;

/**
 * Review lock service for entering edit mode and acquiring the review lock, exposing three contract endpoints.
 * <p>The RedisReentrantLock holds the lock with a 30-minute TTL, and returns 409 with lock-holder information when the lock is already occupied.
 * Heartbeat renewal returns 409 for non-holders, and release is idempotent with no side effects for non-holders.
 * <p>Permission checks for project membership and review eligibility are performed on the admin side, and this service only handles lock semantics.
 *
 */
public interface ReviewLockService {

    /** Acquiring the lock is reentrant, so repeated acquisition by the same user succeeds as lease renewal, while acquisition by another user returns 40904 with a human-readable message containing the lock holder's display name. */
    ReviewLockInfo acquire(long caseId, long userId);

 /** Heartbeat renewal is driven by a frontend 60-second timer and returns 409 with a human-readable message when no lock exists or the caller is not the lock holder. */
    ReviewLockInfo heartbeat(long caseId, long userId);

    /** Active release after leaving the page or submitting returns silently with no side effects for non-holders, following idempotent 204 semantics. */
    void release(long caseId, long userId);
}
