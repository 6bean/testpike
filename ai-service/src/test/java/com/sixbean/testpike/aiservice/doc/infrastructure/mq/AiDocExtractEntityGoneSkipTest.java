package com.sixbean.testpike.aiservice.doc.infrastructure.mq;

import com.sixbean.testpike.aiservice.doc.domain.service.DocExtractService;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.idempotent.IdempotentAspect;
import com.sixbean.testpike.common.idempotent.MqIdempotentConsumeSupport;
import com.sixbean.testpike.common.lock.RedisReentrantLock;
import com.sixbean.testpike.common.mq.AiDocExtractMessage;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F2 extraction consumer entity-gone noise-reduction segment (AJ batch
 * ENTITY_GONE_SKIPPED outcome (target document already deleted), the
 * consumer must ack (no rethrow, so the message never burns retry quota nor
 * re-enters the dead-letter ledger) AND leave a traceable skip audit over
 * POST /internal/audit-logs — silent drops are forbidden, same posture as
 * the duplicate_drop audit right above it.
 * <p>Local Redis 6379 (real lock/idempotency layer) + local HTTP stub for
 * the core audit door (environment-level stand-in, not a business mock) —
 * posture identical to {@link CoreGatewayFrameLedgerTest} on the wire side
 * and {@link AiDocExtractConsumeIdempotencyTest} on the harness side.
 */
class AiDocExtractEntityGoneSkipTest {

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;
    private static HttpServer auditStub;
    private static volatile String lastPath;
    private static volatile String lastBody;
    private static final long DOC_BASE = 930_000_000L + (System.nanoTime() % 50_000_000L);

    @BeforeAll
    static void setUp() throws IOException {
        factory = new LettuceConnectionFactory("localhost", 6379);
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        auditStub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        auditStub.createContext("/", AiDocExtractEntityGoneSkipTest::handleAudit);
        auditStub.start();
    }

    @AfterAll
    static void tearDown() {
        if (auditStub != null) {
            auditStub.stop(0);
        }
        if (factory != null) {
            factory.destroy();
        }
    }

    private static void handleAudit(HttpExchange exchange) throws IOException {
        lastPath = exchange.getRequestURI().toString();
        lastBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        byte[] ok = "{\"code\":\"0\",\"msg\":\"ok\",\"data\":null}".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, ok.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(ok);
        }
    }

    /** Fake extraction service: every call fails with an entity-gone business message. */
    private static class EntityGoneExtractService implements DocExtractService {
        @Override
        public void extract(AiDocExtractMessage message) {
            throw new ServiceException("目标文档不存在或已被删除", 50003);
        }

        @Override
        public void handleTerminalFailure(AiDocExtractMessage message, Exception escapingFailure) {
            // Fixture pins the ack-and-skip path only; the terminal hook's own
            // behavior lives in the dedicated DD item ㊀ fixtures.
        }
    }

    private AiDocExtractConsumer harness() {
        MqIdempotentConsumeSupport support = new MqIdempotentConsumeSupport(new RedisReentrantLock(redis));
        AspectJProxyFactory pf = new AspectJProxyFactory(
                new AiDocExtractIdempotentRunner(new EntityGoneExtractService()));
        pf.addAspect(new IdempotentAspect(redis));
        pf.setProxyTargetClass(true);
        String base = "http://127.0.0.1:" + auditStub.getAddress().getPort();
        return new AiDocExtractConsumer(pf.getProxy(), support, new CoreGateway(base));
    }

    @Test
    void entityGone_failure_isAckedAndAuditedAsSkip() {
        lastPath = null;
        lastBody = null;
        AiDocExtractConsumer consumer = harness();
        long docId = DOC_BASE + 21;
        AiDocExtractMessage message = new AiDocExtractMessage(docId, 1L, 1L, null, UUID.randomUUID().toString());

        assertDoesNotThrow(() -> consumer.onMessage(message),
                "entity-gone failures must never rethrow (rethrow = retry burn = dead-letter ledger noise)");
        assertNotNull(lastBody, "entity-gone skip must leave an audit — silent drop is forbidden");
        assertTrue(lastPath.endsWith("/internal/audit-logs"), "skip audit rides the hash-chain door");
        assertTrue(lastBody.contains("MQ_ENTITY_GONE_SKIP"), "action marker: " + lastBody);
        assertTrue(lastBody.contains("ai-doc-extract:" + docId), "objectId mirrors the duplicate_drop line: " + lastBody);
    }
}
