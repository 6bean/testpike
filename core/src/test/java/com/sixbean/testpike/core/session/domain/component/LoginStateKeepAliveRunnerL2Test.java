package com.sixbean.testpike.core.session.domain.component;

import com.sun.net.httpserver.HttpServer;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.session.api.dto.LoginStateInput;
import com.sixbean.testpike.core.session.domain.service.LoginStateService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L2 contract for the login-state ledger keep-alive round (XC batch).
 *
 * <p>Posture: real dev DB, real HTTP transport against a loopback
 * stand-in target (the JDK server IS the target - nothing about the
 * platform is faked). The scheduler shell is switched off for the whole
 * class (testpike.scheduling.enabled=false) so only the explicit
 * {@link LoginStateKeepAliveRunner#runRound()} calls under test touch
 * the ledger rows.
 *
 * <p>Assertions pin the scenario matrix of the design ruling:
 * VALID keeps the row alive and zeroes the streak; every EXPIRED
 * evidence shape (401 / 2xx login shell / 3xx login bounce) double-stops
 * the row (status=0 AND keepalive off AND a stop reason); UNKNOWN/FAILED
 * ride the counting channel only - the row stays enabled and verified,
 * the streak grows with exponential backoff capped at 4x, and a fresh
 * round must not re-fire before the backed-off window elapses; reaching
 * the fail-streak bound stops the keep-alive WITHOUT touching status;
 * rows past their absolute expiry or manually disabled see zero
 * outbound requests; the global hot switch parks the whole round; and
 * the event ledger stays empty on plain rounds (frames are written
 * only on stop transitions).
 */
@SpringBootTest(classes = CoreApplication.class,
        properties = {"testpike.scheduling.enabled=false"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LoginStateKeepAliveRunnerL2Test {

    private static final String TOKEN_VALUE = "ka-tok-4c1d-88af";
    private static final String GLOBAL_ENABLED_KEY = "session.keepalive.enabled";

    private static long project;
    private static String base;
    private static HttpServer server;

    private static final AtomicInteger LIVE_HITS = new AtomicInteger();
    private static final AtomicInteger DENIED_HITS = new AtomicInteger();
    private static final AtomicInteger SHELL_HITS = new AtomicInteger();
    private static final AtomicInteger BOUNCE_HITS = new AtomicInteger();
    private static final AtomicInteger WEIRD_HITS = new AtomicInteger();

    @Autowired LoginStateKeepAliveRunner runner;
    @Autowired LoginStateService loginStateService;
    @Autowired com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder configCache;
    @Autowired JdbcTemplate jdbc;

    @BeforeAll
    void setUp() throws Exception {
        project = jdbc.queryForObject(
                "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1) RETURNING id",
                Long.class, "ka-xc-" + System.nanoTime(), "kax" + System.nanoTime());
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ka/live", exchange -> {
            LIVE_HITS.incrementAndGet();
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/ka/denied", exchange -> {
            DENIED_HITS.incrementAndGet();
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
        });
        server.createContext("/ka/shell", exchange -> {
            SHELL_HITS.incrementAndGet();
            byte[] body = ("<html><head><title>Login</title></head>"
                    + "<body><input type=\"password\" id=\"pwd\"></body></html>")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/ka/bounce", exchange -> {
            BOUNCE_HITS.incrementAndGet();
            exchange.getResponseHeaders().add("Location", base + "/login?next=ka");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/ka/weird", exchange -> {
            WEIRD_HITS.incrementAndGet();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        base = KeepAliveFixtureLifecycle.startAndGetBase(server);
    }

    @AfterAll
    void tearDown() {
        KeepAliveFixtureLifecycle.teardownFixture(server, jdbc, project);
        jdbc.update("UPDATE tp_sys_config SET config_value = '1' WHERE config_key = ?", GLOBAL_ENABLED_KEY);
    }

    /** Registers a token row with keep-alive columns preset to a due state. */
    private long keepAliveRow(String name, String url) {
        loginStateService.register(project, new LoginStateInput(name, "ka", "保活行",
                "token", "ka_cred_" + name, TOKEN_VALUE, null,
                List.of(base), "L2 keep-alive row"));
        Long id = jdbc.queryForObject(
                "SELECT id FROM tp_login_state WHERE project_id = ? AND state_name = ?",
                Long.class, project, name);
        jdbc.update("UPDATE tp_login_state SET keepalive_enabled = 1, keepalive_url = ? WHERE id = ?",
                url, id);
        return id;
    }

    private String verdictOf(long id) {
        return jdbc.queryForObject(
                "SELECT last_keepalive_verdict FROM tp_login_state WHERE id = ?", String.class, id);
    }

    private int streakOf(long id) {
        return jdbc.queryForObject(
                "SELECT keepalive_fail_streak FROM tp_login_state WHERE id = ?", Integer.class, id);
    }

    private int enabledOf(long id) {
        return jdbc.queryForObject(
                "SELECT keepalive_enabled FROM tp_login_state WHERE id = ?", Integer.class, id);
    }

    private int statusOf(long id) {
        return jdbc.queryForObject(
                "SELECT status FROM tp_login_state WHERE id = ?", Integer.class, id);
    }

    private String stopReasonOf(long id) {
        return jdbc.queryForObject(
                "SELECT keepalive_stop_reason FROM tp_login_state WHERE id = ?", String.class, id);
    }

    private int frameCount(long id) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM tp_ai_task_event WHERE job_kind = 'login-state-keepalive' AND job_ref = ?",
                Integer.class, String.valueOf(id));
    }

    @Test
    void valid_round_keeps_row_alive_and_clears_streak() {
        long id = keepAliveRow("ka_valid", base + "/ka/live");
        jdbc.update("UPDATE tp_login_state SET keepalive_fail_streak = 3 WHERE id = ?", id);

        int processed = runner.runRound();
        assertTrue(processed >= 1);
        assertEquals("VALID", verdictOf(id));
        assertEquals(0, streakOf(id));
        assertEquals(1, enabledOf(id));
        assertEquals(1, statusOf(id));
        assertNotNull(jdbc.queryForObject(
                "SELECT last_keepalive_at FROM tp_login_state WHERE id = ?",
                java.sql.Timestamp.class, id));
        assertNull(stopReasonOf(id));
        // plain rounds never touch the event ledger (per-round zero writes)
        assertEquals(0, frameCount(id));
    }

    @Test
    void expired_401_double_stops_the_row_and_writes_one_frame() {
        long id = keepAliveRow("ka_401", base + "/ka/denied");
        int before = DENIED_HITS.get();

        runner.runRound();
        assertEquals(before + 1, DENIED_HITS.get());
        assertEquals("EXPIRED", verdictOf(id));
        assertEquals(0, statusOf(id), "expired verdict must mark the row verified-invalid");
        assertEquals(0, enabledOf(id), "expired verdict must stop the keep-alive (double stop)");
        assertNotNull(stopReasonOf(id), "stop reason must name the cause and the remedy path");
        assertTrue(frameCount(id) >= 1, "a stop transition writes a replay frame");
    }

    @Test
    void expired_2xx_login_shell_also_double_stops() {
        long id = keepAliveRow("ka_shell", base + "/ka/shell");
        runner.runRound();
        assertEquals("EXPIRED", verdictOf(id));
        assertEquals(0, statusOf(id));
        assertEquals(0, enabledOf(id));
    }

    @Test
    void expired_3xx_login_bounce_also_double_stops() {
        long id = keepAliveRow("ka_bounce", base + "/ka/bounce");
        runner.runRound();
        assertEquals("EXPIRED", verdictOf(id));
        assertEquals(0, enabledOf(id));
    }

    @Test
    void unknown_5xx_counts_and_backs_off_without_touching_status() {
        long id = keepAliveRow("ka_unknown", base + "/ka/weird");
        int before = WEIRD_HITS.get();

        runner.runRound();
        assertEquals(before + 1, WEIRD_HITS.get());
        assertEquals("UNKNOWN", verdictOf(id));
        assertEquals(1, streakOf(id));
        assertEquals(1, statusOf(id), "UNKNOWN is not evidence of rejection - the row stays verified");
        assertEquals(1, enabledOf(id));

        // backed-off window (2x the interval) must park an immediate re-run
        int hits = WEIRD_HITS.get();
        runner.runRound();
        assertEquals(hits, WEIRD_HITS.get(), "row just run must not be due again before the backoff window");
        assertEquals(1, streakOf(id));
    }

    @Test
    void failed_transport_shares_the_counting_channel_and_never_voids_the_row() {
        long id = keepAliveRow("ka_failed", "http://127.0.0.1:1/unreachable");
        runner.runRound();
        assertEquals("FAILED", verdictOf(id));
        assertEquals(1, streakOf(id));
        assertEquals(1, statusOf(id), "unreachable is not a rejection - self-adjudication D1: never void");
        assertEquals(1, enabledOf(id));
    }

    @Test
    void fail_streak_reaching_the_bound_parks_keepalive_but_keeps_status() {
        long id = keepAliveRow("ka_bound", base + "/ka/weird");
        jdbc.update("UPDATE tp_login_state SET keepalive_fail_streak = 5 WHERE id = ?", id);

        runner.runRound();
        assertEquals("UNKNOWN", verdictOf(id));
        assertEquals(6, streakOf(id));
        assertEquals(0, enabledOf(id), "streak bound must stop the keep-alive");
        assertEquals(1, statusOf(id), "the circuit stop must NOT void the login state itself");
        assertNotNull(stopReasonOf(id));
        assertTrue(frameCount(id) >= 1);
    }

    @Test
    void absolute_expiry_and_disabled_rows_see_zero_outbound() {
        long live = LIVE_HITS.get();
        long expiredRow = keepAliveRow("ka_abs_expiry", base + "/ka/live");
        jdbc.update("UPDATE tp_login_state SET expires_at = now() - interval '1 hour' WHERE id = ?",
                expiredRow);
        long disabledRow = keepAliveRow("ka_status_off", base + "/ka/live");
        jdbc.update("UPDATE tp_login_state SET status = 0 WHERE id = ?", disabledRow);

        runner.runRound();
        assertEquals(live, LIVE_HITS.get(), "expired and disabled rows must not fire any request");
        assertNull(jdbc.queryForObject(
                "SELECT last_keepalive_at FROM tp_login_state WHERE id = ?",
                java.sql.Timestamp.class, expiredRow));
    }

    @Test
    void global_hot_switch_parks_the_whole_round() {
        long id = keepAliveRow("ka_gate", base + "/ka/live");
        // The hot read path is the config cache single source (getValue
        // serves it first), so the test flips the gate through the same
        // holder the runtime doors write - a raw SQL update would leave a
        // stale cached "1" behind and prove nothing.
        configCache.put(GLOBAL_ENABLED_KEY, "0");
        try {
            assertEquals(0, runner.runRound());
            assertNull(verdictOf(id));
        } finally {
            configCache.remove(GLOBAL_ENABLED_KEY);
        }
        runner.runRound();
        assertEquals("VALID", verdictOf(id));
    }

    @Test
    void row_level_interval_override_and_backoff_cap_are_applied() throws IOException {
        long id = keepAliveRow("ka_override", base + "/ka/weird");
        // row interval 5 minutes (overrides the factory 10), streak 4 ->
        // multiplier is capped at 4x, so the next due moment is 20 minutes
        // after the last run, not 80.
        jdbc.update("UPDATE tp_login_state SET keepalive_interval_minutes = 5, keepalive_fail_streak = 4 WHERE id = ?", id);
        runner.runRound();
        assertEquals(5, streakOf(id));

        jdbc.update("UPDATE tp_login_state SET last_keepalive_at = now() - interval '25 minutes' WHERE id = ?", id);
        runner.runRound();
        assertEquals(6, streakOf(id), "25 minutes must already be due under the 4x cap of a 5-minute row");
    }
}
