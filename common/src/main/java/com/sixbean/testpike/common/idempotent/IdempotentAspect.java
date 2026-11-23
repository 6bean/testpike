package com.sixbean.testpike.common.idempotent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Collections;
import java.util.HexFormat;
import java.util.UUID;


@Aspect
@Component
public class IdempotentAspect {

    private static final Logger log = LoggerFactory.getLogger(IdempotentAspect.class);

    
    public static final String HEADER_NAME = "Idempotency-Key";

    /** Idempotent marker key namespace (prefix + business key). */
    public static final String KEY_PREFIX = "idempotent:";
    private static final String EXECUTING_PREFIX = "E:";
    private static final String DONE_PREFIX = "D:";

    
    private static final DefaultRedisScript<Long> DELETE_IF_TOKEN_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end return 0",
            Long.class);

    /**
 * takeover primitive: delete the marker only while it is
 * still an in-flight {@code E:} stamp (any token). The done marker
 * {@code D:} is the replay anchor and this call never erases it; the
 * caller (consume guard) must have proven the holder's heartbeat dead
 * before invoking, hence no owner-token check here.
 */
    private static final DefaultRedisScript<Long> DELETE_IF_INFLIGHT_SCRIPT = new DefaultRedisScript<>(
            "local v = redis.call('GET', KEYS[1]) "
                    + "if v and string.sub(v, 1, 2) == 'E:' then return redis.call('DEL', KEYS[1]) end "
                    + "return 0",
            Long.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExpressionParser parser = new SpelExpressionParser();
    private final ParameterNameDiscoverer paramNameDiscoverer = new DefaultParameterNameDiscoverer();

    public IdempotentAspect(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * Clear an orphan in-flight marker for the given business key (no
     * {@code idempotent:} prefix here — same string the consumer hands to
     * the dedup layer). True = an E: stamp was erased, false = nothing was
     * in flight or the marker already settled to the done anchor.
     */
    public boolean clearOrphanInFlight(String businessKey) {
        Long removed = redis.execute(DELETE_IF_INFLIGHT_SCRIPT,
                Collections.singletonList(KEY_PREFIX + businessKey));
        return removed != null && removed > 0;
    }

    @Around("@annotation(idempotent)")
    public Object around(ProceedingJoinPoint pjp, Idempotent idempotent) throws Throwable {
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        String redisKey = KEY_PREFIX + resolveKey(signature, pjp.getArgs(), idempotent);
        String token = EXECUTING_PREFIX + UUID.randomUUID();
        Duration window = Duration.ofSeconds(idempotent.ttlSeconds());

        Boolean acquired = redis.opsForValue().setIfAbsent(redisKey, token,
                resolveInflightTtl(signature, pjp.getArgs(), idempotent, window));
        if (!Boolean.TRUE.equals(acquired)) {
            String existing = redis.opsForValue().get(redisKey);
            if (existing != null && existing.startsWith(DONE_PREFIX)) {
                return replay(signature, existing.substring(DONE_PREFIX.length()));
            }
            throw new ServiceException("重复请求：相同 Idempotency-Key 的请求正在处理中，请勿重复提交",
                    BizErrorCode.IDEMPOTENT_DUPLICATE);
        }

        Object result;
        try {
            result = pjp.proceed();
        } catch (Throwable ex) {
            redis.execute(DELETE_IF_TOKEN_SCRIPT, Collections.singletonList(redisKey), token);
            throw ex;
        }
        try {
            redis.opsForValue().set(redisKey, DONE_PREFIX + mapper.writeValueAsString(result), window);
        } catch (Exception e) {
            log.warn("幂等结果序列化失败（回放将返回空）key={} err={}", redisKey, e.getMessage());
            redis.opsForValue().set(redisKey, DONE_PREFIX + "null", window);
        }
        return result;
    }

    /**
 * in-flight ({@code E:}) marker window. Blank expression,
 * garbage or non-positive values fall back to the annotation window —
 * every usage without {@code inflightTtl} stays byte-identical to before.
 */
    private Duration resolveInflightTtl(MethodSignature signature, Object[] args,
                                        Idempotent idempotent, Duration window) {
        String expr = idempotent.inflightTtl();
        if (expr.isBlank()) {
            return window;
        }
        try {
            var ctx = new MethodBasedEvaluationContext(
                    null, signature.getMethod(), args, paramNameDiscoverer);
            Object value = parser.parseExpression(expr).getValue(ctx);
            long seconds = value instanceof Number n ? n.longValue()
                    : Long.parseLong(String.valueOf(value).trim());
            return seconds > 0 ? Duration.ofSeconds(seconds) : window;
        } catch (Exception e) {
            log.warn("inflightTtl SpEL unresolved, falling back to the done window "
                    + "method={} expr={} err={}", signature.getName(), expr, e.getMessage());
            return window;
        }
    }

    
    private String resolveKey(MethodSignature signature, Object[] args, Idempotent idempotent) {
        String header = headerKey();
        if (header != null && !header.isBlank()) {
            return header.trim();
        }
        if (!idempotent.key().isBlank()) {
            var ctx = new MethodBasedEvaluationContext(
                    null, signature.getMethod(), args, paramNameDiscoverer);
            Object value = parser.parseExpression(idempotent.key()).getValue(ctx);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value);
            }
        }
        return signature.getMethod().getDeclaringClass().getSimpleName()
                + "#" + signature.getName() + "#" + sha256Hex(java.util.Arrays.toString(args));
    }

    
    private static String headerKey() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return attrs.getRequest().getHeader(HEADER_NAME);
        }
        return null;
    }

    
    private Object replay(MethodSignature signature, String json) throws Exception {
        Class<?> returnType = signature.getReturnType();
        if (returnType == void.class || returnType == Void.class || "null".equals(json)) {
            return null;
        }
        return mapper.readValue(json, returnType);
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
