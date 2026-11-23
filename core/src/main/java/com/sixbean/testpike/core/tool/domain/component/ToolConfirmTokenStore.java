package com.sixbean.testpike.core.tool.domain.component;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import com.sixbean.testpike.core.config.domain.component.TtlSecondsPolicy;
import com.sixbean.testpike.core.tool.api.dto.ToolConfirmTicket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;


@Component
public class ToolConfirmTokenStore {

    
    static final String KEY_PREFIX = "tool:confirm:";

 
    public static final String TTL_KEY = "ai.tool.confirm-ttl-seconds";

    
    static final long DEFAULT_TTL_SECONDS = 300L;

    private static final Logger log = LoggerFactory.getLogger(ToolConfirmTokenStore.class);

    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redis;
    private final ConfigCacheHolder configCacheHolder;

    public ToolConfirmTokenStore(StringRedisTemplate redis,
                                 ConfigCacheHolder configCacheHolder) {
        this.redis = redis;
        this.configCacheHolder = configCacheHolder;
    }

    
    public ToolConfirmTicket issue(long toolId) {
        long ttl = ttlSeconds();
        byte[] buf = new byte[32];
        RANDOM.nextBytes(buf);
        String token = HexFormat.of().formatHex(buf);
        try {
            redis.opsForValue().set(KEY_PREFIX + token, String.valueOf(toolId),
                    Duration.ofSeconds(ttl));
        } catch (RuntimeException e) {
            log.warn("确认票签发失败（Redis 不可用）toolId={}: {}", toolId, e.getMessage());
            throw new ServiceException("写确认服务暂不可用：工具「" + toolId + "」的确认票未能保存（缓存服务暂不可达），"
                    + "本次写入已停止——请稍后在「AI 对话」重新发起确认；多次仍未恢复请联系管理员检查 Redis 缓存服务",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return new ToolConfirmTicket(token, ttl);
    }

    
    public void consume(String token, long toolId) {
        String bound;
        try {
            bound = redis.opsForValue().getAndDelete(KEY_PREFIX + token);
        } catch (RuntimeException e) {
            log.warn("确认票消费失败（Redis 不可用）toolId={}: {}", toolId, e.getMessage());
            throw new ServiceException("写确认服务暂不可用：确认票未能校验（缓存服务暂不可达），"
                    + "本次写入已停止——请稍后在「AI 对话」重新发起确认；多次仍未恢复请联系管理员检查 Redis 缓存服务",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (bound == null) {
            throw new ServiceException("写确认票无效或已过期——工具「需人工确认」后才可执行，"
                    + "请重新发起确认", BizErrorCode.VALIDATION_ERROR);
        }
        if (!String.valueOf(toolId).equals(bound)) {
            throw new ServiceException("写确认票与本工具不匹配（已拒绝执行）",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    
    long ttlSeconds() {
        String raw = configCacheHolder.get(TTL_KEY);
        return TtlSecondsPolicy.resolve(raw, DEFAULT_TTL_SECONDS);
    }
}
