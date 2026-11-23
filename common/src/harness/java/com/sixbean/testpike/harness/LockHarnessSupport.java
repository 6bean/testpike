package com.sixbean.testpike.harness;

import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;


public final class LockHarnessSupport {

    private LockHarnessSupport() {
    }

    
    public static StringRedisTemplate connect() {
        String host = System.getenv().getOrDefault("TP_REDIS_HOST", "localhost");
        int port = Integer.parseInt(System.getenv().getOrDefault("TP_REDIS_PORT", "6379"));
        LettuceConnectionFactory factory = new LettuceConnectionFactory(host, port);
        factory.afterPropertiesSet();
        StringRedisTemplate redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        return redis;
    }

    
    public static void ok(String step) {
        System.out.println("OK " + step);
    }

    
    public static void fail(String step, String detail) {
        System.out.println("FAIL " + step + " :: " + detail);
        System.exit(1);
    }
}
