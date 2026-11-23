package com.sixbean.testpike.common.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Locks the shutdown-logging prewarm contract.
 *
 * <p>During a graceful stop the SpringApplicationShutdownHook may need classes that the logging
 * pipeline touches only when an exception event is rendered (ThrowableProxy and friends). Inside a
 * fat jar launched JVM those classes were never loaded before, and the lazy first load during JVM
 * exit died with NoClassDefFoundError, silently dropping all shutdown diagnostics. The prewarm must
 * therefore load every candidate eagerly at startup, and must stay silent when the logging backend
 * is absent (for example a different binding on the classpath) instead of throwing.
 */
class ShutdownLoggingPrewarmTest {

    @Test
    void loadsEveryShutdownLazyLogbackClassWhenBackendPresent() {
        List<String> loaded = new ShutdownLoggingPrewarm()
                .preload(ShutdownLoggingPrewarmTest.class.getClassLoader());
        // surefire runs on the logback backend shipped by spring-boot-starter-logging, so every
        // candidate is expected to load; a miss here means the candidate list references a class
        // that no longer exists in the logback version on the classpath.
        assertEquals(ShutdownLoggingPrewarm.SHUTDOWN_LAZY_CLASSES.size(), loaded.size(),
                "expected every candidate class to load, got " + loaded);
        assertTrue(loaded.contains("ch.qos.logback.classic.spi.ThrowableProxy"),
                "ThrowableProxy is the class observed missing in the shutdown-hook stack trace");
    }

    @Test
    void skipsQuietlyWhenBackendAbsent() {
        // Hit case for the skip branch (R43): a bootstrap-only class loader cannot see any logback
        // class, so the prewarm must return nothing and must not throw.
        ClassLoader bootstrapOnly = new ClassLoader(null) {
        };
        List<String> loaded = new ShutdownLoggingPrewarm().preload(bootstrapOnly);
        assertTrue(loaded.isEmpty(), "no class should load through a bootstrap-only loader");
    }
}
