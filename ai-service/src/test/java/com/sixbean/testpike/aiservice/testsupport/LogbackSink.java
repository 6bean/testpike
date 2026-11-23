package com.sixbean.testpike.aiservice.testsupport;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Shared logback capture sink for L2 suites that assert on WARN anchors
 * emitted through the production slf4j logger of a service class.
 * <p>First consumer: the structured-decode enforcement notice suite. Later
 * suites must consume this single source instead of re-copying the
 * ListAppender scaffold (R40 — the DocExtract inline copy is folded into the
 * shared-captors ledger migration, not duplicated a third time here).
 * <p>Usage: {@code try (LogbackSink sink = LogbackSink.attach(Service.class))}
 * around the call under test; {@link #warnsContaining} then reads the WARN
 * lines formatted with the real placeholder semantics.
 */
public final class LogbackSink implements AutoCloseable {

    private final Logger logger;
    private final ListAppender<ILoggingEvent> appender;

    private LogbackSink(Logger logger, ListAppender<ILoggingEvent> appender) {
        this.logger = logger;
        this.appender = appender;
    }

    /** Attach a live list appender to the logback logger of the given class. */
    public static LogbackSink attach(Class<?> owner) {
        Logger logger = (Logger) LoggerFactory.getLogger(owner);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return new LogbackSink(logger, appender);
    }

    /** Formatted WARN messages captured since attach (live view). */
    public List<String> warns() {
        return appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.toList());
    }

    /** Captured WARN messages carrying the given marker substring. */
    public List<String> warnsContaining(String marker) {
        return warns().stream()
                .filter(m -> m.contains(marker))
                .collect(Collectors.toList());
    }

    /** Formatted INFO messages captured since attach (live view). */
    public List<String> infos() {
        return appender.list.stream()
                .filter(e -> e.getLevel() == Level.INFO)
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.toList());
    }

    /** Captured INFO messages carrying the given marker substring. */
    public List<String> infosContaining(String marker) {
        return infos().stream()
                .filter(m -> m.contains(marker))
                .collect(Collectors.toList());
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
        appender.stop();
    }
}
