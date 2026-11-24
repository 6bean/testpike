package com.sixbean.testpike.core.session.domain.component;

import com.sun.net.httpserver.HttpServer;
import com.sixbean.testpike.common.exception.ServiceException;
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

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L2 contract for the keep-alive enable door (design ruling section 5).
 *
 * <p>Posture mirrors the keep-alive round test: real dev DB, real HTTP
 * transport against a loopback stand-in target, scheduler shell off.
 * The stand-in distinguishes the material round from the material-free
 * round by the presence of the Authorization header, so the double
 * pre-check runs through the same transport the scheduler will use.
 *
 * <p>Pinned outcomes: a protected resource enables the row and stamps it;
 * a login-exempt address (2xx both ways) is refused; rejected material is
 * refused AND voids the row through the single writeback door; a 2xx
 * login shell shown to the material round is the same refusal family;
 * an unreachable target is refused WITHOUT touching the verified status;
 * an UNKNOWN answer enables with a note; an out-of-range interval and a
 * missing address are refused before any outbound request; and a manual
 * disable flips the switch with zero traffic and clears the stop reason.
 */
@SpringBootTest(classes = CoreApplication.class,
        properties = {"testpike.scheduling.enabled=false"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LoginStateKeepAliveEnableGateL2Test {

    private static final String TOKEN_VALUE = "ka-en-tok-9d3e-47b2";
    private static final String DEAD_URL = "http://127.0.0.1:1/ka/en/unreachable";

    private static long project;
    private static String base;
    private static HttpServer server;

    private static final AtomicInteger PROTECTED_HITS = new AtomicInteger();
    private static final AtomicInteger PUBLIC_HITS = new AtomicInteger();
    private static final AtomicInteger DENIED_HITS = new AtomicInteger();
    private static final AtomicInteger SHELL_MATERIAL_HITS = new AtomicInteger();
    private static final AtomicInteger WEIRD_HITS = new AtomicInteger();
    private static final AtomicInteger SHELL_ANON_HITS = new AtomicInteger();

    @Autowired LoginStateKeepAliveEnableGate gate;
    @Autowired LoginStateService loginStateService;
    @Autowired JdbcTemplate jdbc;

    @BeforeAll
    void setUp() throws Exception {
        project = jdbc.queryForObject(
                "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1) RETURNING id",
                Long.class, "ka-en-" + System.nanoTime(), "kaen" + System.nanoTime());
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ka/en/protected", exchange -> {
            PROTECTED_HITS.incrementAndGet();
            boolean withMaterial = exchange.getRequestHeaders().getFirst("Authorization") != null;
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            if (withMaterial) {
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } else {
                exchange.sendResponseHeaders(401, -1);
            }
            exchange.close();
        });
        server.createContext("/ka/en/public", exchange -> {
            PUBLIC_HITS.incrementAndGet();
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/ka/en/denied", exchange -> {
            DENIED_HITS.incrementAndGet();
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
        });
        server.createContext("/ka/en/shell-material", exchange -> {
            SHELL_MATERIAL_HITS.incrementAndGet();
            byte[] body = ("<html><head><title>Login</title></head>"
                    + "<body><input type=\"password\" id=\"pwd\"></body></html>")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/ka/en/weird", exchange -> {
            WEIRD_HITS.incrementAndGet();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.createContext("/ka/en/shell-anon", exchange -> {
            SHELL_ANON_HITS.incrementAndGet();
            boolean withMaterial = exchange.getRequestHeaders().getFirst("Authorization") != null;
            byte[] ok = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            byte[] shell = ("<html><head><title>Login</title></head>"
                    + "<body><input type=\"password\" id=\"pwd\"></body></html>")
                    .getBytes(StandardCharsets.UTF_8);
            if (withMaterial) {
                exchange.sendResponseHeaders(200, ok.length);
                exchange.getResponseBody().write(ok);
            } else {
                exchange.sendResponseHeaders(200, shell.length);
                exchange.getResponseBody().write(shell);
            }
            exchange.close();
        });
        base = KeepAliveFixtureLifecycle.startAndGetBase(server);
    }

    @AfterAll
    void tearDown() {
        KeepAliveFixtureLifecycle.teardownFixture(server, jdbc, project);
    }

    /** Registers a token row; the keep-alive columns stay factory-off. */
    private long row(String name) {
        String credKey = ("ka_en_cred_" + name).replace('-', '_');
        loginStateService.register(project, new LoginStateInput(name, "ka", "保活开启预检行",
                "token", credKey, TOKEN_VALUE, null,
                List.of(base), "L2 enable-gate row"));
        return jdbc.queryForObject(
                "SELECT id FROM tp_login_state WHERE project_id = ? AND state_name = ?",
                Long.class, project, name);
    }

    private void presetUrl(long id, String url) {
        jdbc.update("UPDATE tp_login_state SET keepalive_url = ? WHERE id = ?", url, id);
    }

    private int enabledOf(long id) {
        return jdbc.queryForObject("SELECT keepalive_enabled FROM tp_login_state WHERE id = ?",
                Integer.class, id);
    }

    private int statusOf(long id) {
        return jdbc.queryForObject("SELECT status FROM tp_login_state WHERE id = ?", Integer.class, id);
    }

    private String verdictOf(long id) {
        return jdbc.queryForObject(
                "SELECT last_keepalive_verdict FROM tp_login_state WHERE id = ?", String.class, id);
    }

    private String stopReasonOf(long id) {
        return jdbc.queryForObject(
                "SELECT keepalive_stop_reason FROM tp_login_state WHERE id = ?", String.class, id);
    }

    private Integer intervalOf(long id) {
        return jdbc.queryForObject(
                "SELECT keepalive_interval_minutes FROM tp_login_state WHERE id = ?", Integer.class, id);
    }

    private int frameCount(long id) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM tp_ai_task_event WHERE job_kind = 'login-state-keepalive'"
                        + " AND job_ref = ?", Integer.class, String.valueOf(id));
    }

    private static int totalHits() {
        return PROTECTED_HITS.get() + PUBLIC_HITS.get() + DENIED_HITS.get()
                + SHELL_MATERIAL_HITS.get() + WEIRD_HITS.get() + SHELL_ANON_HITS.get();
    }

    @Test
    void protected_resource_passes_precheck_and_enables_the_row() {
        long id = row("gate-protected");
        gate.apply(id, true, base + "/ka/en/protected", 15);
        assertEquals(1, enabledOf(id), "pre-check passed must flip the switch");
        assertEquals("VALID", verdictOf(id));
        assertEquals(15, intervalOf(id));
        assertNull(stopReasonOf(id));
        assertEquals(1, statusOf(id), "an enabled door must not void the row");
        assertTrue(frameCount(id) >= 1, "enable transition writes one frame");
    }

    @Test
    void login_exempt_address_is_refused_without_touching_the_row() {
        long id = row("gate-public");
        int hits = totalHits();
        int frames = frameCount(id);
        ServiceException refused = assertThrows(ServiceException.class,
                () -> gate.apply(id, true, base + "/ka/en/public", null));
        assertTrue(refused.getMessage().contains("不校验登录态"), refused.getMessage());
        assertEquals(0, enabledOf(id));
        assertNull(verdictOf(id));
        assertEquals(hits + 2, totalHits(), "both rounds must have run");
        assertEquals(frames, frameCount(id), "a refusal is not a transition");
    }

    @Test
    void rejected_material_refuses_enable_and_voids_the_row() {
        long id = row("gate-denied");
        ServiceException refused = assertThrows(ServiceException.class,
                () -> gate.apply(id, true, base + "/ka/en/denied", null));
        assertTrue(refused.getMessage().contains("会话材料被目标拒绝"), refused.getMessage());
        assertTrue(refused.getMessage().contains("重新采集"), refused.getMessage());
        assertEquals(0, enabledOf(id));
        assertEquals(0, statusOf(id), "EXPIRED evidence rides the single writeback door");
    }

    @Test
    void login_shell_shown_to_the_material_round_refuses_and_voids_the_row() {
        long id = row("gate-shellmat");
        ServiceException refused = assertThrows(ServiceException.class,
                () -> gate.apply(id, true, base + "/ka/en/shell-material", null));
        assertTrue(refused.getMessage().contains("会话材料被目标拒绝"), refused.getMessage());
        assertEquals(0, enabledOf(id));
        assertEquals(0, statusOf(id));
    }

    @Test
    void unreachable_target_refuses_enable_without_voiding_the_row() {
        long id = row("gate-dead");
        ServiceException refused = assertThrows(ServiceException.class,
                () -> gate.apply(id, true, DEAD_URL, null));
        assertTrue(refused.getMessage().contains("没有够到目标"), refused.getMessage());
        assertEquals(0, enabledOf(id));
        assertEquals(1, statusOf(id), "unreachable is not rejection evidence (D1)");
    }

    @Test
    void unknown_answer_enables_with_a_note_and_skips_the_material_free_round() {
        long id = row("gate-weird");
        int hits = WEIRD_HITS.get();
        gate.apply(id, true, base + "/ka/en/weird", null);
        assertEquals(1, enabledOf(id));
        assertEquals("UNKNOWN", verdictOf(id));
        assertEquals(hits + 1, WEIRD_HITS.get(), "second round is pointless for UNKNOWN");
        assertTrue(frameCount(id) >= 1);
    }

    @Test
    void anonymous_login_shell_counts_as_protected_and_enables() {
        long id = row("gate-shellanon");
        int hits = SHELL_ANON_HITS.get();
        gate.apply(id, true, base + "/ka/en/shell-anon", null);
        assertEquals(1, enabledOf(id));
        assertEquals("VALID", verdictOf(id));
        assertEquals(hits + 2, SHELL_ANON_HITS.get(), "material + material-free rounds");
    }

    @Test
    void out_of_range_interval_is_refused_before_any_traffic() {
        long id = row("gate-range");
        int hits = totalHits();
        ServiceException refused = assertThrows(ServiceException.class,
                () -> gate.apply(id, true, base + "/ka/en/protected", 2));
        assertTrue(refused.getMessage().contains("5~1440"), refused.getMessage());
        assertEquals(0, enabledOf(id));
        assertEquals(hits, totalHits(), "validation must short-circuit before probing");
    }

    @Test
    void missing_url_is_refused_with_a_named_remedy() {
        long id = row("gate-nourl");
        int hits = totalHits();
        ServiceException refused = assertThrows(ServiceException.class,
                () -> gate.apply(id, true, null, null));
        assertTrue(refused.getMessage().contains("缺少保活地址"), refused.getMessage());
        assertTrue(refused.getMessage().contains("编辑"), refused.getMessage());
        assertEquals(0, enabledOf(id));
        assertEquals(hits, totalHits());
    }

    @Test
    void stored_url_is_used_when_the_request_omits_one() {
        long id = row("gate-stored");
        presetUrl(id, base + "/ka/en/protected");
        gate.apply(id, true, null, 30);
        assertEquals(1, enabledOf(id));
        assertEquals("VALID", verdictOf(id));
        assertEquals(30, intervalOf(id));
    }

    @Test
    void manual_disable_flips_the_switch_with_zero_traffic_and_clears_the_reason() {
        long id = row("gate-disable");
        gate.apply(id, true, base + "/ka/en/protected", null);
        jdbc.update("UPDATE tp_login_state SET keepalive_stop_reason = '旧停因' WHERE id = ?", id);
        int hits = totalHits();
        int frames = frameCount(id);
        gate.apply(id, false, null, null);
        assertEquals(0, enabledOf(id));
        assertNull(stopReasonOf(id));
        assertEquals(hits, totalHits(), "disable never probes");
        assertTrue(frameCount(id) > frames, "disable transition writes one frame");
    }
}
