package com.sixbean.testpike.aiservice.testsupport;

import org.slf4j.Logger;
import org.slf4j.helpers.MessageFormatter;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

/**
 * Cross-package shared slf4j sink for the log-collapse L2 suites
 * promotion — the MQ dead-letter handler is the second-family consumer, so the
 * captor leaves the llm in-package form per the DUP ledger and R40; suites
 * must consume this single source, no re-copies).
 * <p>A dynamic-proxy {@link Logger} that records every warn(...) and error(...)
 * call (formatted with slf4j placeholder semantics; the Throwable attached to
 * error(String, Throwable) is captured separately as the stack) and answers
 * false/empty for all other levels. Chosen over hand-implementing the full
 * Logger surface (dozens of Marker overloads across slf4j versions) and over
 * per-suite logback ListAppender copies. Consumers inject {@link #logger}
 * into the component constructors under test; the underlying lists are live,
 * so the view returned at construction time stays correct.
 */
public final class CapturingLogger implements java.lang.reflect.InvocationHandler {

    private final List<String> warns;
    private final List<String> errors;
    private final List<Throwable> errorStacks;
    private final Logger logger;
    private Throwable pendingErrorStack;

    public CapturingLogger() {
        this.warns = new java.util.ArrayList<>();
        this.errors = new java.util.ArrayList<>();
        this.errorStacks = new java.util.ArrayList<>();
        this.logger = (Logger) Proxy.newProxyInstance(
                Logger.class.getClassLoader(), new Class<?>[]{Logger.class}, this);
    }

    /** Captured WARN lines in emission order (live view). */
    public List<String> warns() {
        return warns;
    }

    /** Captured ERROR lines in emission order (live view). */
    public List<String> errors() {
        return errors;
    }

    /** Throwable attached to each captured ERROR call (null when none);
     *  index-aligned with {@link #errors()}. */
    public List<Throwable> errorStacks() {
        return errorStacks;
    }

    /** The proxy {@link Logger} to inject into the component under test. */
    public Logger logger() {
        return logger;
    }

    /** WARN/ERROR family records, every other level resolves to a harmless default. */
    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
        String name = method.getName();
        if (name.equals("warn")) {
            warns.add(format(args));
            return null;
        }
        if (name.equals("error")) {
            pendingErrorStack = null;
            errors.add(format(args));
            errorStacks.add(pendingErrorStack);
            return null;
        }
        if (name.startsWith("is") && name.endsWith("Enabled")) {
            return name.startsWith("isWarn") || name.startsWith("isError");
        }
        return switch (name) {
            case "getName" -> "capture";
            case "toString" -> "CapturingLogger";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == (args == null ? null : args[0]);
            default -> null;
        };
    }

    /** slf4j placeholder formatting for a warn(...) argument vector. */
    private String format(Object[] args) {
        if (args == null || args.length == 0) {
            return "";
        }
        if (args.length == 1) {
            return String.valueOf(args[0]);
        }
        // warn/error(String, Throwable) carries no placeholders: keep the message,
        // capture the stack separately (surfaced through errorStacks for ERROR).
        if (args.length == 2 && args[1] instanceof Throwable throwable) {
            pendingErrorStack = throwable;
            return String.valueOf(args[0]);
        }
        Object[] formatArgs = new Object[args.length - 1];
        System.arraycopy(args, 1, formatArgs, 0, formatArgs.length);
        return MessageFormatter.arrayFormat(String.valueOf(args[0]), formatArgs).getMessage();
    }
}
