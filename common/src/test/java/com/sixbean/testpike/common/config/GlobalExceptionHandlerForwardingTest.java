package com.sixbean.testpike.common.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.exception.BizErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;

import java.net.ConnectException;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Downstream forwarding failure typing : the executor protocol relay storm
 * (api -> core claim/heartbeat every 2s while core is down) used to fall into the
 * catch-all handler and dump a full stack for every single request (309 identical
 * ERROR lines in one window). Contract under test:
 * 1. ResourceAccessException is typed as "downstream service unreachable" with the
 * target named on a single line, no stack attached (stack = noise, not detail).
 * 2. Repeats with the same fingerprint collapse inside a configurable window:
 * only the window-open line is emitted; the rollover line carries the count of
 * suppressed repeats. Nothing is silently swallowed.
 * 3. Genuinely unexpected exceptions keep the full stack (guard: catch-all untouched).
 * Response contract to callers is deliberately unchanged (50000 "system busy") so
 * executor retry semantics and E2E assertions do not drift with log hygiene.
 */
class GlobalExceptionHandlerForwardingTest {

    private ListAppender<ILoggingEvent> appender;
    private Logger handlerLogger;

    @BeforeEach
    void attachAppender() {
        handlerLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        appender = new ListAppender<>();
        appender.start();
        handlerLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        handlerLogger.detachAppender(appender);
        appender.stop();
    }

    private static ResourceAccessException stormLike(String methodAndUrl) {
        return new ResourceAccessException(
                "I/O error on " + methodAndUrl + ": null", new ConnectException("Connection refused"));
    }

    @Test
    void forwardingFailureIsTypedSingleLineWithoutStack() {
        AtomicLong clock = new AtomicLong(0L);
        GlobalExceptionHandler handler =
                new GlobalExceptionHandler(new DownstreamForwardingGuard(clock::get, 60_000L));

        ResponseEntity<Response<Void>> resp = handler.handleDownstreamFailure(
                stormLike("POST request for \"http://localhost:8083/internal/executors/register\""));

        // Caller-visible contract unchanged: 500 + 50000 "system busy", no internal leak.
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.getStatusCode());
        assertEquals(String.valueOf(BizErrorCode.INTERNAL_ERROR), resp.getBody().getCode());
        assertTrue(!resp.getBody().getMsg().contains("8083"), "响应不泄露下游地址细节");

        List<ILoggingEvent> events = appender.list;
        assertEquals(1, events.size(), "首现应产出一条分型行");
        ILoggingEvent first = events.get(0);
        assertEquals(Level.ERROR, first.getLevel());
        String msg = first.getFormattedMessage();
        assertTrue(msg.contains("下游服务不可达"), "分型行必须点名故障类别: " + msg);
        assertTrue(msg.contains("localhost:8083/internal/executors/register"), "分型行必须点名下游目标: " + msg);
        assertTrue(msg.contains("ConnectException"), "分型行必须点名根因类型: " + msg);
        assertNull(first.getThrowableProxy(), "转发失败类禁倾倒完整堆栈（风暴本体）");
    }

    @Test
    void repeatsWithinWindowCollapseAndRolloverCarriesCount() {
        AtomicLong clock = new AtomicLong(1_000L);
        GlobalExceptionHandler handler =
                new GlobalExceptionHandler(new DownstreamForwardingGuard(clock::get, 60_000L));
        ResourceAccessException ex =
                stormLike("POST request for \"http://localhost:8083/internal/executors/heartbeat\"");

        handler.handleDownstreamFailure(ex);              // window opens, 1 line
        for (int i = 0; i < 29; i++) {
            clock.set(1_000L + i * 2_000L);               // ~every 2s like the real storm
            handler.handleDownstreamFailure(ex);
        }
        assertEquals(1, appender.list.size(), "60s 窗内同指纹 30 次只出 1 行");

        clock.set(61_001L);                               // window expired, next hit rolls over
        handler.handleDownstreamFailure(ex);
        assertEquals(2, appender.list.size(), "超窗后下一条必须补报折叠账，禁静默吞");
        String rollover = appender.list.get(1).getFormattedMessage();
        assertTrue(rollover.contains("窗内折叠"), "换行必标注折叠语义: " + rollover);
        assertTrue(rollover.contains("29"), "换行必带被折叠条数: " + rollover);
    }

    @Test
    void differentTargetsAreIndependentFingerprints() {
        AtomicLong clock = new AtomicLong(0L);
        GlobalExceptionHandler handler =
                new GlobalExceptionHandler(new DownstreamForwardingGuard(clock::get, 60_000L));

        handler.handleDownstreamFailure(stormLike("POST request for \"http://localhost:8083/a\""));
        handler.handleDownstreamFailure(stormLike("POST request for \"http://localhost:8083/b\""));

        assertEquals(2, appender.list.size(), "不同下游目标=不同指纹，禁互相吞");
    }

    @Test
    void genuinelyUnexpectedExceptionKeepsFullStack() {
        GlobalExceptionHandler handler =
                new GlobalExceptionHandler(new DownstreamForwardingGuard(System::currentTimeMillis, 60_000L));

        handler.handleException(new IllegalStateException("真实缺陷样例"));

        assertEquals(1, appender.list.size());
        assertNotNull(appender.list.get(0).getThrowableProxy(), "真未预期异常必须保留完整堆栈");
        assertNotNull(handler.handleDownstreamFailure(
                stormLike("GET request for \"http://localhost:8083/c\"")));
    }
}
