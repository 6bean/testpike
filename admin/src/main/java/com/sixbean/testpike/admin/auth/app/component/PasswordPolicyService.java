package com.sixbean.testpike.admin.auth.app.component;

import com.sixbean.testpike.admin.user.api.dto.PasswordStatusView;
import com.sixbean.testpike.admin.user.infrastructure.remote.client.CoreUserClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;


public class PasswordPolicyService {

    private static final Logger log = LoggerFactory.getLogger(PasswordPolicyService.class);

    
    public static final String CACHE_KEY_PREFIX = "auth:pwd-expired:";
    static final long CACHE_TTL_SECONDS = 5;

    private final CoreUserClient coreUserClient;
    private final StringRedisTemplate redis;

    public PasswordPolicyService(CoreUserClient coreUserClient, StringRedisTemplate redis) {
        this.coreUserClient = coreUserClient;
        this.redis = redis;
    }

    
    public boolean isExpired(long userId) {
        try {
            String cached = redis.opsForValue().get(CACHE_KEY_PREFIX + userId);
            if (cached != null) {
                return "1".equals(cached);
            }
            PasswordStatusView status = coreUserClient.passwordStatus(userId);
            redis.opsForValue().set(CACHE_KEY_PREFIX + userId,
                    status.passwordExpired() ? "1" : "0", Duration.ofSeconds(CACHE_TTL_SECONDS));
            return status.passwordExpired();
        } catch (com.sixbean.testpike.common.exception.ServiceException e) {
            if (com.sixbean.testpike.common.exception.BizErrorCode.NOT_FOUND == e.getCode()) {
                return true;
            }
            log.warn("密码过期判定降级（core 异常，不误杀会话）userId={}", userId, e);
            return false;
        } catch (RuntimeException e) {
            log.warn("密码过期判定降级（core/Redis 不可用，不误杀会话）userId={}", userId, e);
            return false;
        }
    }

    
    public void evict(long userId) {
        try {
            redis.delete(CACHE_KEY_PREFIX + userId);
        } catch (RuntimeException e) {
            log.warn("过期判定缓存失效失败（TTL 5s 自然过期兜底）userId={}", userId, e);
        }
    }

    
    public Long passwordChangedAtEpoch(long userId) {
        try {
            LocalDateTime changedAt = coreUserClient.getUser(userId).passwordChangedAt();
            return changedAt == null ? null : changedAt.atZone(ZoneId.systemDefault()).toEpochSecond();
        } catch (RuntimeException e) {
            log.warn("密码计时点读取失败（会话跳过 R5 检查，不阻断登录）userId={}", userId, e);
            return null;
        }
    }
}
