package com.sixbean.testpike.common.log;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Preloads the logging classes a graceful shutdown needs before the JVM stops loading classes.
 *
 * <p>Rendering an exception log event touches logback internals only when an exception is actually
 * logged. When a fat-jar service is stopped, the SpringApplicationShutdownHook closes the context
 * and the first shutdown-time exception log triggered a lazy class load that could no longer be
 * served, killing the hook thread with NoClassDefFoundError and silently dropping every remaining
 * shutdown diagnostic. Loading the candidates once at startup keeps shutdown logging alive.
 */
@Component
public class ShutdownLoggingPrewarm {

    /**
     * Classes on the shutdown-time exception-logging path, listed in the order the logging event
     * pipeline reaches them. Candidates that are absent on the classpath (different logging
     * binding) are skipped quietly by {@link #preload(ClassLoader)}.
     */
    static final List<String> SHUTDOWN_LAZY_CLASSES = List.of(
            "ch.qos.logback.classic.spi.ThrowableProxy",
            "ch.qos.logback.classic.spi.StackTraceElementProxy",
            "ch.qos.logback.classic.spi.ThrowableProxyUtil");

    @PostConstruct
    void preloadOnStartup() {
        preload(ShutdownLoggingPrewarm.class.getClassLoader());
    }

    /**
     * Force-loads every candidate visible to the given class loader.
     *
     * @param classLoader the loader used for the lookup; a loader without the logging backend
     *        simply yields no results instead of failing startup
     * @return the class names that were successfully loaded, in candidate order
     */
    public List<String> preload(ClassLoader classLoader) {
        List<String> loaded = new ArrayList<>();
        for (String name : SHUTDOWN_LAZY_CLASSES) {
            try {
                Class.forName(name, true, classLoader);
                loaded.add(name);
            } catch (ClassNotFoundException | NoClassDefFoundError quietlyAbsent) {
                // Backend not on this classpath: nothing to prewarm, startup stays unaffected.
            }
        }
        return loaded;
    }
}
