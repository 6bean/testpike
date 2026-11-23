package com.sixbean.testpike.core.cases.domain.service.impl;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.lock.RedisReentrantLock;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.api.dto.ReviewLockInfo;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.cases.domain.service.ReviewLockService;
import com.sixbean.testpike.core.user.api.dto.UserInfo;
import com.sixbean.testpike.core.user.domain.service.UserService;
import org.springframework.stereotype.Service;

/**
 * Review lock service implementation (E10; PRD 5.2 plus the three-endpoint semantics of contract 02).
 * <p>The lock key is {@code review:case:{id}} (RedisReentrantLock automatically adds the lock: prefix), and ownerId is a userId string.
 * The TTL is fixed at 30min (PRD E10: automatic release after 30 minutes of inactivity plus heartbeat renewal).
 * <p>Re-acquisition means renewal without incrementing the count: each HTTP endpoint request is an independent owner session, and using tryLock reentrant counting is incorrect.
 * A page refresh would require two releases, and a single release would permanently leak the lock, so an owner hit uses renew (atomic renewal, count always 1).
 * <p>The 409 human-readable message includes the lock holder's display name (PRD 4.2.4: 'This use case is being reviewed by the holder'); releasing by a non-holder is silent with no side effects.
 * This is idempotent semantics.
 *
 */
@Service
public class ReviewLockServiceImpl implements ReviewLockService {

    /** The review lock TTL in milliseconds is fixed at 30min by contract 02. */
    static final long TTL_MILLIS = 30 * 60 * 1000L;

    private final RedisReentrantLock redisLock;
    private final CaseService caseService;
    private final UserService userService;

    public ReviewLockServiceImpl(RedisReentrantLock redisLock, CaseService caseService,
                                 UserService userService) {
        this.redisLock = redisLock;
        this.caseService = caseService;
        this.userService = userService;
    }

    @Override
    public ReviewLockInfo acquire(long caseId, long userId) {
        CaseInfo caseInfo = caseService.get(caseId); // Return 40401 when it does not exist (do not create a ghost lock).
        String key = lockKey(caseInfo.id());
        String owner = String.valueOf(userId);
        // First probe the owner: if the current user already holds it, use renew (count always 1, see the class comment on the lock-leak pin; tryLock reentrancy would add +1 and is unusable).
        String current = redisLock.ownerOf(key);
        if (owner.equals(current)) {
            redisLock.renew(key, owner, TTL_MILLIS);
            return info(caseInfo.id(), userId);
        }
        if (redisLock.tryLock(key, owner, TTL_MILLIS)) {
            return info(caseInfo.id(), userId);
        }
        // tryLock failure means another party acquired it within the race window (or the current user acquired it first); after rechecking ownership, use a unified 409 branch.
        if (owner.equals(redisLock.ownerOf(key))) {
            redisLock.renew(key, owner, TTL_MILLIS);
            return info(caseInfo.id(), userId);
        }
        throw occupied(caseInfo.id(), key);
    }

    @Override
    public ReviewLockInfo heartbeat(long caseId, long userId) {
        CaseInfo caseInfo = caseService.get(caseId);
        String key = lockKey(caseInfo.id());
        if (!redisLock.renew(key, String.valueOf(userId), TTL_MILLIS)) {
 // If there is no lock (TTL expired) or the caller is not the holder, return a 409 human-readable message with no side effects, and the frontend exits edit mode accordingly.
            String currentOwner = redisLock.ownerOf(key);
            if (currentOwner == null) {
                throw new ServiceException("评审锁已过期，请重新进入编辑模式",
                        BizErrorCode.RESOURCE_CONFLICT);
            }
            throw occupied(caseInfo.id(), key);
        }
        return info(caseInfo.id(), userId);
    }

    @Override
    public void release(long caseId, long userId) {
        CaseInfo caseInfo = caseService.get(caseId);
        // Idempotent release: the unlock Lua owner check naturally rejects non-holders (returning false with no side effects), and silence means 204 semantics.
        redisLock.unlock(lockKey(caseInfo.id()), String.valueOf(userId));
    }

    /** The lock key name is consistent with the @AfterEach cleanup key and cross-process observation. */
    static String lockKey(long caseId) {
        return "review:case:" + caseId;
    }

    /** The 40904 human-readable message for an occupied lock contains the lock holder's display name (fallback to username when display_name is empty). */
    private ServiceException occupied(long caseId, String key) {
        String owner = redisLock.ownerOf(key);
        if (owner == null) {
            // If tryLock fails and the lock is released in the query gap, do not falsely report the name in extreme races, and return a human-readable retry prompt.
            throw new ServiceException("该用例（ID " + caseId + "）的评审锁状态已变化（他人刚释放）——"
                    + "请在「评审工作台」重新打开该用例再次获取评审锁", BizErrorCode.RESOURCE_CONFLICT);
        }
        UserInfo holder = userService.get(Long.parseLong(owner));
        String name = holder.displayName() == null || holder.displayName().isBlank()
                ? holder.username() : holder.displayName();
        throw new ServiceException("该用例正在被 " + name + " 评审，仅可只读查看",
                BizErrorCode.RESOURCE_CONFLICT);
    }

    private ReviewLockInfo info(long caseId, long userId) {
        UserInfo holder = userService.get(userId);
        String name = holder.displayName() == null || holder.displayName().isBlank()
                ? holder.username() : holder.displayName();
        Long remaining = redisLock.getLeaseRemainingMillis(lockKey(caseId));
        return new ReviewLockInfo(caseId, userId, name, remaining == null ? 0L : remaining);
    }
}
