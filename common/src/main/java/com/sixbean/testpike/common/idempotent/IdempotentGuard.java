package com.sixbean.testpike.common.idempotent;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;
import java.util.function.Supplier;


@Component
public class IdempotentGuard {

    private static final Logger log = LoggerFactory.getLogger(IdempotentGuard.class);

    
    private static final Duration TTL = Duration.ofHours(24);

    private static final String KEY_PREFIX = "idempotent:";
    private static final String EXECUTING_PREFIX = "E:";
    private static final String DONE_PREFIX = "D:";

    
    private static final DefaultRedisScript<Long> DELETE_IF_TOKEN_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end return 0",
            Long.class);

    private final StringRedisTemplate redis;

    
    private final ObjectMapper mapper = new ObjectMapper().activateDefaultTyping(
            BasicPolymorphicTypeValidator.builder()
                    .allowIfSubType("java.lang.")
                    .allowIfSubType("java.util.")
                    .allowIfSubType("com.sixbean.testpike.")
                    .build(),
            ObjectMapper.DefaultTyping.EVERYTHING,
            JsonTypeInfo.As.PROPERTY);

    public IdempotentGuard(StringRedisTemplate redis) {
        this.redis = redis;
    }

    
    @SuppressWarnings("unchecked")
    public <T> T execute(String key, Supplier<T> supplier) {
        String redisKey = KEY_PREFIX + key;
        String token = EXECUTING_PREFIX + UUID.randomUUID();

        Boolean acquired = redis.opsForValue().setIfAbsent(redisKey, token, TTL);
        if (!Boolean.TRUE.equals(acquired)) {
            String existing = redis.opsForValue().get(redisKey);
            if (existing != null && existing.startsWith(DONE_PREFIX)) {
                return (T) replay(existing.substring(DONE_PREFIX.length()));
            }
            throw new ServiceException("重复请求：相同 Idempotency-Key 的请求正在处理中，请勿重复提交",
                    BizErrorCode.IDEMPOTENT_DUPLICATE);
        }

        T result;
        try {
            result = supplier.get();
        } catch (RuntimeException ex) {
            redis.execute(DELETE_IF_TOKEN_SCRIPT, Collections.singletonList(redisKey), token);
            throw ex;
        }
        try {
            redis.opsForValue().set(redisKey, DONE_PREFIX + mapper.writeValueAsString(result), TTL);
        } catch (Exception e) {
            log.warn("幂等结果序列化失败（回放将返回空）key={} err={}", redisKey, e.getMessage());
            redis.opsForValue().set(redisKey, DONE_PREFIX + "null", TTL);
        }
        return result;
    }

    
    private Object replay(String json) {
        if ("null".equals(json)) {
            return null;
        }
        try {
            return mapper.readValue(json, Object.class);
        } catch (Exception e) {
            log.warn("幂等回放反序列化失败（返回空）err={}", e.getMessage());
            return null;
        }
    }
}
