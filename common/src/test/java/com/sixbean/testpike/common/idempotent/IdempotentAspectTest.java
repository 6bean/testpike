package com.sixbean.testpike.common.idempotent;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class IdempotentAspectTest {

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;
    private static final String RUN_ID = UUID.randomUUID().toString().substring(0, 8);

    @BeforeAll
    static void connect() {
        factory = new LettuceConnectionFactory("localhost", 6379);
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void disconnect() {
        if (factory != null) {
            factory.destroy();
        }
    }

    private IdempotentDemoService proxyWith(IdempotentDemoService target) {
        AspectJProxyFactory pf = new AspectJProxyFactory(target);
        pf.addAspect(new IdempotentAspect(redis));
        pf.setProxyTargetClass(true);
        return pf.getProxy();
    }


    @Test
    void duplicateAfterCompletion_replaysFirstResult() {
        IdempotentDemoService target = new IdempotentDemoService();
        IdempotentDemoService proxy = proxyWith(target);
        String key = "idem:" + RUN_ID + ":replay";

        assertEquals("echo:" + key, proxy.echo(key));
        assertEquals(1, target.invocations());
        assertEquals("echo:" + key, proxy.echo(key));
        assertEquals(1, target.invocations(), "重复提交不得二次执行业务");
        String other = "idem:" + RUN_ID + ":other";
        assertEquals("echo:" + other, proxy.echo(other));
        assertEquals(2, target.invocations());
    }


    @Test
    void concurrentDuplicate_returns409AndExecutesOnce() throws Exception {
        IdempotentDemoService target = new IdempotentDemoService();
        IdempotentDemoService proxy = proxyWith(target);
        String key = "idem:" + RUN_ID + ":concurrent";

        CountDownLatch done = new CountDownLatch(2);
        AtomicReference<Object> firstResult = new AtomicReference<>();
        AtomicReference<ServiceException> secondError = new AtomicReference<>();
        AtomicInteger unexpectedErrors = new AtomicInteger();

        Thread first = new Thread(() -> {
            try {
                firstResult.set(proxy.slowEcho(key));
            } catch (Exception e) {
                unexpectedErrors.incrementAndGet();
            } finally {
                done.countDown();
            }
        });
        Thread second = new Thread(() -> {
            try {
                Thread.sleep(150);
                proxy.slowEcho(key);
            } catch (ServiceException e) {
                secondError.set(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        });
        first.start();
        second.start();
        done.await();

        assertEquals(0, unexpectedErrors.get(), "首发不得失败");
        assertEquals("slow:" + key, firstResult.get());
        ServiceException ex = secondError.get();
        assertInstanceOf(ServiceException.class, ex, "在途重复提交必须 409");
        assertEquals(BizErrorCode.IDEMPOTENT_DUPLICATE, ex.getCode(),
                "错误码=40903 幂等重复提交");
        assertTrue(ex.getMessage().contains("处理中") || ex.getMessage().contains("重复"),
                "人话消息: " + ex.getMessage());
        assertEquals(1, target.invocations(), "并发双发业务只执行一次");

        assertEquals("slow:" + key, proxy.slowEcho(key));
        assertEquals(1, target.invocations(), "完成后的重放同样不再执行");
    }


    @Test
    void failureNotCached_retryAllowed() {
        IdempotentDemoService target = new IdempotentDemoService();
        IdempotentDemoService proxy = proxyWith(target);
        String key = "idem:" + RUN_ID + ":fail";

        assertThrows(IllegalStateException.class, () -> proxy.alwaysFails(key));
        assertEquals(1, target.invocations());
        assertThrows(IllegalStateException.class, () -> proxy.alwaysFails(key));
        assertEquals(2, target.invocations(), "失败不缓存，重试应再次进入业务");
    }


    @Test
    void structuredResultReplay_deserializesCorrectly() {
        IdempotentDemoService target = new IdempotentDemoService();
        IdempotentDemoService proxy = proxyWith(target);
        String key = "idem:" + RUN_ID + ":map";

        Map<String, Object> first = proxy.stats(key);
        Map<String, Object> replay = proxy.stats(key);
        assertEquals(first, replay, "回放结果与原结果相等");
        assertEquals(42, ((Number) replay.get("count")).intValue());
        assertEquals(key, replay.get("name"));
        assertEquals(1, target.invocations());
    }


    @Test
    void keyExpiry_allowsReuse() throws Exception {
        IdempotentDemoService target = new IdempotentDemoService();
        IdempotentDemoService proxy = proxyWith(target);
        String key = "idem:" + RUN_ID + ":ttl";

        assertEquals("echo:" + key, proxy.echoWithShortTtl(key));
        Thread.sleep(1300);
        assertEquals("echo:" + key, proxy.echoWithShortTtl(key));
        assertEquals(2, target.invocations(), "键过期后同键应重新执行而非回放");
    }


    @Test
    void derivedKey_fromArgs_whenAnnotationKeyBlank() {
        IdempotentDemoService target = new IdempotentDemoService();
        IdempotentDemoService proxy = proxyWith(target);

        String a = "derived-" + RUN_ID;
        String b = "other-" + RUN_ID;
        assertEquals("echo:" + a, proxy.echoDerived(a));
        assertEquals("echo:" + a, proxy.echoDerived(a), "同入参派生键重放");
        assertEquals("echo:" + b, proxy.echoDerived(b), "异入参派生键互不干扰");
        assertEquals(2, target.invocations());
    }


    @Test
    void headerKey_takesPrecedenceOverArgDerivedSpel() {
        IdempotentDemoService target = new IdempotentDemoService();
        IdempotentDemoService proxy = proxyWith(target);
        String headerKey = "idem:" + RUN_ID + ":hdr-explicit";

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Idempotency-Key", headerKey);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            assertEquals("echo:v1", proxy.echo("v1"));
            assertEquals("echo:v1", proxy.echo("v2"), "头键相同=同一幂等记录，回放首发结果");
            assertEquals(1, target.invocations(), "头键命中不得二次执行");
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }
}
