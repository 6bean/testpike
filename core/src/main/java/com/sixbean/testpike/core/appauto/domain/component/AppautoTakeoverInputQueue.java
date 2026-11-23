package com.sixbean.testpike.core.appauto.domain.component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.appauto.domain.service.AppautoTakeoverService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;


@Component
public class AppautoTakeoverInputQueue {

    private static final Logger log = LoggerFactory.getLogger(AppautoTakeoverInputQueue.class);

    
    public static final String INPUT_KEY_PREFIX = "appauto-takeover:input:";

    private final StringRedisTemplate redis;
    private final AppautoConfigReader configReader;
    private final ObjectMapper objectMapper;

    public AppautoTakeoverInputQueue(StringRedisTemplate redis,
                                     AppautoConfigReader configReader,
                                     ObjectMapper objectMapper) {
        this.redis = redis;
        this.configReader = configReader;
        this.objectMapper = objectMapper;
    }

    
    public static String inputKey(long requestId) {
        return INPUT_KEY_PREFIX + requestId;
    }

    
    public long size(long requestId) {
        Long n = redis.opsForList().size(inputKey(requestId));
        return n == null ? 0L : n;
    }

    
    public void enqueue(long requestId, List<Map<String, Object>> commands) {
        if (commands == null || commands.isEmpty()) {
            return;
        }
        List<String> json = new ArrayList<>(commands.size());
        for (Map<String, Object> cmd : commands) {
            try {
                json.add(objectMapper.writeValueAsString(cmd));
            } catch (Exception e) {
                log.warn("接管输入指令序列化失败（requestId={}）跳过: {}", requestId, e.getMessage());
            }
        }
        if (json.isEmpty()) {
            return;
        }
        String key = inputKey(requestId);
        redis.opsForList().rightPushAll(key, json);
        redis.expire(key, Duration.ofSeconds(configReader.takeoverInputTtlSeconds()));
    }

    
    public List<Map<String, Object>> drain(long requestId, int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < limit; i++) {
            String raw = redis.opsForList().leftPop(inputKey(requestId));
            if (raw == null) {
                break;
            }
            try {
                out.add(objectMapper.readValue(raw, new TypeReference<Map<String, Object>>() {
                }));
            } catch (Exception e) {
                log.warn("接管输入指令反序列化失败（requestId={}）诚实丢弃: {}", requestId, e.getMessage());
            }
        }
        return out;
    }

    
    public void clear(long requestId) {
        redis.delete(inputKey(requestId));
    }

    
    public int ttlSeconds() {
        return configReader.takeoverInputTtlSeconds();
    }

    
    public int queueMax() {
        return configReader.takeoverInputQueueMax();
    }
}
