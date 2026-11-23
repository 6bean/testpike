package com.sixbean.testpike.core.cases.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.lock.RedisReentrantLock;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseStepInput;
import com.sixbean.testpike.core.cases.api.dto.ReviewLockInfo;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.support.ProjectFixtureRig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class ReviewLockServiceTest {

    
    private static final long TTL_MILLIS = 30 * 60 * 1000L;

    @Autowired
    private ReviewLockService reviewLockService;

    @Autowired
    private CaseService caseService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private RedisReentrantLock redisLock;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long holderId;
    private long otherId;
    private final java.util.Set<Long> touchedCaseIds = new java.util.HashSet<>();
    private final java.util.List<Long> lockTrackedProjects =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    @BeforeEach
    @AfterEach
    void cleanTables() {
        for (Long caseId : touchedCaseIds) {
            redisTemplate.delete(RedisReentrantLock.LOCK_KEY_PREFIX + "review:case:" + caseId);
        }
        touchedCaseIds.clear();
        // Drop this suite's own test users first (FK hygiene on the shared dev db),
        // then sweep only rows reachable from tracked fixture projects (:
        // blanket TRUNCATE of factory tables is banned).
        jdbcTemplate.update("DELETE FROM tp_user_role WHERE user_id IN (SELECT id FROM tp_user WHERE username IN ('p2holder','p2other'))");
        jdbcTemplate.update("DELETE FROM tp_user WHERE username IN ('p2holder','p2other')");
        ProjectFixtureRig.sweep(jdbcTemplate, lockTrackedProjects);
        holderId = upsertUser("p2holder", "锁持有者甲");
        otherId = upsertUser("p2other", "锁竞争者乙");
    }

    private long upsertUser(String username, String displayName) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO tp_user (username, display_name, role, status)
                VALUES (?, ?, 2, 1) RETURNING id
                """, Long.class, username, displayName);
    }

    private long createCase(String title) {
        long projectId = ProjectFixtureRig.createSelfHeal(jdbcTemplate, projectService, 1L,
                lockTrackedProjects, "项目L" + title, "L" + title);
        long caseId = caseService.create(1L, new CaseCreateInput(projectId, title, "前置", "预期",
                2, 2, "web", 1, 1, 1, null,
                List.of(new CaseStepInput(1, "步骤1", 0)))).id();
        touchedCaseIds.add(caseId);
        return caseId;
    }

    private static String lockKey(long caseId) {
        return "review:case:" + caseId;
    }

    @Test
    void acquireReturnsHolderWith30MinLease() {
        long caseId = createCase("LKA");

        ReviewLockInfo info = reviewLockService.acquire(caseId, holderId);

        assertEquals(caseId, info.caseId());
        assertEquals(holderId, info.holderId());
        assertEquals("锁持有者甲", info.holderName());
        assertTrue(info.leaseRemainingMillis() > TTL_MILLIS - 30_000
                        && info.leaseRemainingMillis() <= TTL_MILLIS,
                "租期应≈30min 实际=" + info.leaseRemainingMillis());
        assertTrue(redisLock.isLocked(lockKey(caseId)));
        assertEquals(String.valueOf(holderId), redisLock.ownerOf(lockKey(caseId)));
    }

    @Test
    void reAcquireBySameUserRenewsWithoutCounting() {
        long caseId = createCase("LKB");
        reviewLockService.acquire(caseId, holderId);

        ReviewLockInfo again = reviewLockService.acquire(caseId, holderId);

        assertEquals(holderId, again.holderId());
        assertEquals(1, redisLock.holdCount(lockKey(caseId), String.valueOf(holderId)));
        reviewLockService.release(caseId, holderId);
        assertFalse(redisLock.isLocked(lockKey(caseId)), "重取后单次释放应完全放锁");
    }

    @Test
    void otherUserAcquireRejected409WithHolderName() {
        long caseId = createCase("LKC");
        reviewLockService.acquire(caseId, holderId);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> reviewLockService.acquire(caseId, otherId));

        assertEquals(BizErrorCode.RESOURCE_CONFLICT, ex.getCode(), "已占用=40904（HTTP 409）");
        assertTrue(ex.getMessage().contains("锁持有者甲"),
                "人话须含持锁人显示名（4.2.4 张三提示语义）实际=" + ex.getMessage());
        assertEquals(String.valueOf(holderId), redisLock.ownerOf(lockKey(caseId)));
    }

    @Test
    void heartbeatRenewsOwnerAndRejectsOthers() {
        long caseId = createCase("LKD");
        reviewLockService.acquire(caseId, holderId);

        ReviewLockInfo hb = reviewLockService.heartbeat(caseId, holderId);
        assertEquals(holderId, hb.holderId());
        assertTrue(hb.leaseRemainingMillis() > TTL_MILLIS - 30_000, "心跳=续租满 30min");

        ServiceException ex = assertThrows(ServiceException.class,
                () -> reviewLockService.heartbeat(caseId, otherId));
        assertEquals(BizErrorCode.RESOURCE_CONFLICT, ex.getCode());
        assertTrue(ex.getMessage().contains("锁持有者甲"));
    }

    @Test
    void heartbeatWithoutLockRejected409() {
        long caseId = createCase("LKE");

        ServiceException ex = assertThrows(ServiceException.class,
                () -> reviewLockService.heartbeat(caseId, holderId));
        assertEquals(BizErrorCode.RESOURCE_CONFLICT, ex.getCode());
        assertNotNull(ex.getMessage());
        assertFalse(redisLock.isLocked(lockKey(caseId)), "心跳无锁不得副作用建锁");
    }

    @Test
    void releaseIsIdempotentAndNonHolderNoSideEffect() {
        long caseId = createCase("LKF");
        reviewLockService.acquire(caseId, holderId);

        reviewLockService.release(caseId, otherId);
        assertTrue(redisLock.isLocked(lockKey(caseId)), "非持锁人释放不得放锁");

        reviewLockService.release(caseId, holderId);
        assertFalse(redisLock.isLocked(lockKey(caseId)));
        reviewLockService.release(caseId, holderId);
    }

    @Test
    void acquireCaseNotFoundRejected404() {
        long ghostId = 999_999_999L;
        ServiceException ex = assertThrows(ServiceException.class,
                () -> reviewLockService.acquire(ghostId, holderId));
        assertEquals(BizErrorCode.NOT_FOUND, ex.getCode());
        assertFalse(redisLock.isLocked(lockKey(ghostId)), "不存在的用例不得建锁");
    }

    @Test
    void takeoverAfterReleaseSucceeds() {
        long caseId = createCase("LKG");
        reviewLockService.acquire(caseId, holderId);
        reviewLockService.release(caseId, holderId);

        ReviewLockInfo info = reviewLockService.acquire(caseId, otherId);
        assertEquals(otherId, info.holderId());
        assertEquals("锁竞争者乙", info.holderName());
    }
}
