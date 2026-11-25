package com.sixbean.testpike.core.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.Extension;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * Global class-end claim-window hygiene guard (fresh-install core gate
 * triage, r20/r30: representative classes isolate green, the full suite goes
 * red because an earlier suite's leaked pending tp_exec_task rows sit at the
 * head of the claim candidate window — that window is an ORDER BY t.id LIMIT
 * 20 scan with no per-executor capability filter, so twenty unclaimable
 * foreign rows starve every later claim assertion).
 *
 * <p>Test-side fix only, the product SQL stays untouched (registered
 * ruling): after every test class ends, any still-pending task belonging to
 * a project created inside this JVM's run window (primary key above the
 * suite-start floor, so factory and live-service rows can never match, R37)
 * is reaped through the {@link SharedDbFixtureSweep} single source and named
 * in a census line — the class whose end triggered the reap IS the leaking
 * suite, which is the evidence the follow-up teardown fix needs. The
 * window-end gate ({@code tools/verification/exec_claim_window_census.py})
 * re-reads the same shape and asserts zero, so a guard that silently never
 * ran cannot masquerade as a clean window.
 *
 * <p>Registered through ServiceLoader autodetection
 * (core/src/test/resources/junit-platform.properties), so no suite has to
 * extend or annotate anything. Classes without a Spring context never touch
 * the shared database: the context lookup degrades to a no-op here, and each
 * degradation reason is counted per class-end and reported once as an
 * aggregated census summary at JVM shutdown — the skip facts stay observable
 * without the per-class stack-frame noise a full suite would otherwise print
 * hundreds of times.
 */
public final class ExecClaimWindowGuard implements Extension, BeforeAllCallback, AfterAllCallback {

    /** Surefire working directory is the module base, so this lands under core/target/. */
    private static final Path FLOOR_FILE = Path.of("target", "exec-claim-window-floor");
    private static final AtomicLong PROJECT_FLOOR = new AtomicLong(-1L);

    static final String REASON_NO_SPRING_CONTEXT = "guard-skipped-no-spring-context";
    static final String REASON_NO_JDBC_BEAN = "guard-skipped-no-jdbc-bean";
    static final String REASON_FLOOR_FILE_UNWRITABLE = "guard-floor-file-unwritable";

    /** How many class-end skips accumulated per degradation reason. */
    private static final Map<String, LongAdder> SKIP_COUNTS = new ConcurrentHashMap<>();
    /** A bounded per-reason sample of affected classes (evidence, not a whitelist). */
    private static final Map<String, Deque<String>> SKIP_SAMPLES = new ConcurrentHashMap<>();
    private static final int SAMPLE_LIMIT = 5;
    private static final AtomicBoolean SUMMARY_HOOK_INSTALLED = new AtomicBoolean(false);

    @Override
    public void beforeAll(ExtensionContext context) {
        captureFloorIfAbsent(context);
    }

    @Override
    public void afterAll(ExtensionContext context) {
        long floor = PROJECT_FLOOR.get();
        if (floor < 0) {
            return; // no window floor yet: no DB-writing class has run either
        }
        JdbcTemplate jdbc = jdbcTemplateOrNull(context);
        if (jdbc == null) {
            return; // plain unit class: it cannot have touched the shared database
        }
        List<Long> leaked = SharedDbFixtureSweep.pendingTaskIdsAboveProjectFloor(jdbc, floor);
        if (leaked.isEmpty()) {
            return;
        }
        System.out.println("[exec-claim-census] class=" + context.getRequiredTestClass().getName()
                + " leakedPendingTasks=" + leaked.size() + " ids=" + leaked);
        SharedDbFixtureSweep.byIdTree(jdbc, "tp_exec_task", leaked);
    }

    /**
     * Capture max(tp_project.id) at the first moment a Spring-backed class
     * starts: everything the run creates afterwards sits above this floor.
     * Written to {@link #FLOOR_FILE} so the window-end census gate reads the
     * exact same boundary instead of re-deriving one.
     */
    private static void captureFloorIfAbsent(ExtensionContext context) {
        if (PROJECT_FLOOR.get() >= 0) {
            return;
        }
        JdbcTemplate jdbc = jdbcTemplateOrNull(context);
        if (jdbc == null) {
            return;
        }
        Long max = jdbc.queryForObject(
                "SELECT coalesce(max(id), 0) FROM tp_project", Long.class);
        long floor = max == null ? 0L : max;
        if (PROJECT_FLOOR.compareAndSet(-1L, floor)) {
            try {
                Files.createDirectories(FLOOR_FILE.getParent());
                Files.writeString(FLOOR_FILE, Long.toString(floor), StandardCharsets.UTF_8);
            } catch (IOException ex) {
                recordSkip(REASON_FLOOR_FILE_UNWRITABLE,
                        context.getRequiredTestClass().getName(), shortReason(ex));
            }
        }
    }

    /**
     * Resolve the running Spring context's JdbcTemplate through the single
     * sanctioned SpringExtension channel. A class that never used Spring has
     * no context to consult and no shared-database writes to reap, so the
     * lookup failing is the healthy no-op shape — but a context present
     * without a JdbcTemplate bean, or an unexpected failure mode, is logged
     * as a WARN census line instead of being folded into silence.
     */
    private static JdbcTemplate jdbcTemplateOrNull(ExtensionContext context) {
        ApplicationContext appContext;
        try {
            appContext = SpringExtension.getApplicationContext(context);
        } catch (RuntimeException ex) {
            if (rootCauseClassName(ex).startsWith("org.junit")) {
                return null; // not a Spring-managed class
            }
            // Context exists but could not be resolved: the sweep did NOT run
            // for this class. Counted into the shutdown summary — the
            // window-end census gate stays red on a leak, the summary names
            // why the guard cannot have cleared it.
            recordSkip(REASON_NO_SPRING_CONTEXT,
                    context.getRequiredTestClass().getName(), shortReason(ex));
            return null;
        }
        if (appContext == null) {
            return null;
        }
        try {
            return appContext.getBean(JdbcTemplate.class);
        } catch (BeansException ex) {
            recordSkip(REASON_NO_JDBC_BEAN,
                    context.getRequiredTestClass().getName(), shortReason(ex));
            return null;
        }
    }

    /**
     * Aggregate one class-end degradation: counted per reason, with a bounded
     * sample of class names and cause signatures. Nothing is silently folded
     * away — {@link #printSummary()} reports the totals once at JVM shutdown.
     */
    static void recordSkip(String reason, String className, String cause) {
        installSummaryHook();
        SKIP_COUNTS.computeIfAbsent(reason, key -> new LongAdder()).increment();
        Deque<String> samples = SKIP_SAMPLES.computeIfAbsent(reason,
                key -> new java.util.concurrent.ConcurrentLinkedDeque<>());
        if (samples.size() < SAMPLE_LIMIT) {
            samples.add(className + " cause=" + cause);
        }
    }

    private static void installSummaryHook() {
        if (SUMMARY_HOOK_INSTALLED.compareAndSet(false, true)) {
            Runtime.getRuntime().addShutdownHook(
                    new Thread(ExecClaimWindowGuard::printSummary, "exec-claim-census-summary"));
        }
    }

    /** One aggregated line per degradation reason (plus a ran-anchor line). */
    static void printSummary() {
        System.out.println("[exec-claim-census] summary floor="
                + PROJECT_FLOOR.get() + " skippedClasses=" + SKIP_COUNTS.values().stream()
                .mapToLong(LongAdder::longValue).sum());
        for (Map.Entry<String, LongAdder> entry : SKIP_COUNTS.entrySet()) {
            System.out.println("[exec-claim-census] summary reason=" + entry.getKey()
                    + " count=" + entry.getValue().longValue()
                    + " sampleClasses=" + List.copyOf(SKIP_SAMPLES.getOrDefault(entry.getKey(),
                            new ArrayDeque<>())));
        }
    }

    private static String shortReason(Throwable ex) {
        return rootCauseClassName(ex) + ": " + firstLineOfMessage(ex);
    }

    private static String rootCauseClassName(Throwable ex) {
        Throwable t = ex;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getClass().getName();
    }

    private static String firstLineOfMessage(Throwable ex) {
        Throwable t = ex;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String message = t.getMessage();
        if (message == null) {
            return "no-message";
        }
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }

    /** Test seam: read the aggregated counts recorded so far in this JVM. */
    static long skipCount(String reason) {
        LongAdder adder = SKIP_COUNTS.get(reason);
        return adder == null ? 0L : adder.longValue();
    }

    /** Test seam: reset aggregation state between contract tests. */
    static void resetAggregatesForTests() {
        SKIP_COUNTS.clear();
        SKIP_SAMPLES.clear();
    }

    static List<String> sampleOf(String reason) {
        return new ArrayList<>(SKIP_SAMPLES.getOrDefault(reason, new ArrayDeque<>()));
    }
}
