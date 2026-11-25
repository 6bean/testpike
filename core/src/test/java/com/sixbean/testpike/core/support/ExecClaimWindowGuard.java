package com.sixbean.testpike.core.support;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
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
 * the shared database: the context lookup degrades to a no-op here, and any
 * degradation prints one WARN census line instead of failing silently.
 */
public final class ExecClaimWindowGuard implements Extension, BeforeAllCallback, AfterAllCallback {

    /** Surefire working directory is the module base, so this lands under core/target/. */
    private static final Path FLOOR_FILE = Path.of("target", "exec-claim-window-floor");
    private static final AtomicLong PROJECT_FLOOR = new AtomicLong(-1L);

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
                System.out.println("[exec-claim-census] WARN floor-file-unwritable "
                        + FLOOR_FILE + " cause=" + rootCauseOf(ex));
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
            if (rootCauseOf(ex).startsWith("org.junit")) {
                return null; // not a Spring-managed class
            }
            // Context exists but could not be resolved: the sweep did NOT run
            // for this class. Say so — the window-end census gate stays red
            // on a leak, this line names why the guard cannot have cleared it.
            System.out.println("[exec-claim-census] WARN class="
                    + context.getRequiredTestClass().getName()
                    + " guard-skipped-no-spring-context cause=" + rootCauseOf(ex));
            return null;
        }
        if (appContext == null) {
            return null;
        }
        try {
            return appContext.getBean(JdbcTemplate.class);
        } catch (BeansException ex) {
            System.out.println("[exec-claim-census] WARN class="
                    + context.getRequiredTestClass().getName()
                    + " guard-skipped-no-jdbc-bean cause=" + rootCauseOf(ex));
            return null;
        }
    }

    private static String rootCauseOf(Throwable ex) {
        Throwable t = ex;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        String firstFrames = sw.toString().lines().limit(4).reduce("", (a, b) -> a + " | " + b);
        return t.getClass().getName() + firstFrames;
    }
}
