package com.sixbean.testpike.core.credential.domain.service.impl;

import com.sun.net.httpserver.HttpServer;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.config.api.dto.ConfigInput;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import com.sixbean.testpike.core.credential.api.dto.CredentialInput;
import com.sixbean.testpike.core.credential.api.dto.CredentialTestHeaderInput;
import com.sixbean.testpike.core.credential.api.dto.CredentialTestInput;
import com.sixbean.testpike.core.credential.api.dto.CredentialTestOutcome;
import com.sixbean.testpike.core.credential.domain.service.CredentialService;
import com.sixbean.testpike.core.credential.domain.service.CredentialTestService;
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
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L2 contract for the credential vault "test now" door : the verdict
 * rides as a first-class evidence grade, not buried in the trail note.
 *
 * <p>Everything is real: vault encryption plus the reveal audit, a loopback
 * JDK server standing in for the system-under-test (it records the true
 * Authorization / Cookie wire values and serves a login-shell page fixture),
 * and the hot knob for the login-shell fingerprint. Four grades are asserted:
 * VERIFIED (injected, 2xx, not a shell), UNVERIFIED (zero references - the
 * address merely answered), EXPIRED (401/403), UNKNOWN (200 login shell,
 * which must NOT be over-ruled as expired). The header reference area counts
 * against the same three-surface reconciliation as URL and body.
 */
@SpringBootTest(classes = CoreApplication.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CredentialTestEvidenceL2Test {

    private static final String FINGERPRINT_KNOB = "credential.test.login-fingerprint.enabled";

    private static long project;
    private static HttpServer server;
    private static String base;
    private static final AtomicReference<String> AUTH_SEEN = new AtomicReference<>("");
    private static final AtomicReference<String> PATH_SEEN = new AtomicReference<>("");

    @Autowired CredentialTestService testService;
    @Autowired CredentialService credentialService;
    @Autowired ConfigService configService;
    @Autowired JdbcTemplate jdbc;

    @BeforeAll
    static void startTarget() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/biz", exchange -> {
            AUTH_SEEN.set(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/shell", exchange -> {
            AUTH_SEEN.set(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            byte[] body = ("<html><head><title>Sign in</title></head><body>"
                    + "<form action=\"/sso/redirect\"><input type=\"password\" name=\"pwd\">"
                    + "</form></body></html>").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/deny", exchange -> {
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
        });
        server.createContext("/track", exchange -> {
            AUTH_SEEN.set(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            PATH_SEEN.set(exchange.getRequestURI().getPath());
            byte[] body = "{\"tracked\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @BeforeAll
    void setUpProject() {
        project = jdbc.queryForObject(
                "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1) RETURNING id",
                Long.class, "wo-evidence-" + System.nanoTime(), "woe" + System.nanoTime());
    }

    @AfterAll
    static void shutdownTarget() {
        if (server != null) {
            server.stop(0);
        }
    }

    private long newCredential(String label) {
        return credentialService.create(project, new CredentialInput(
                "WO 证据级凭据 " + label, "wo_ev_" + label + "_" + System.nanoTime(),
                "s3cr3t-VALUE-9f2d", "L2 契约行"));
    }

    private String evidenceOf(long credentialId) {
        return jdbc.queryForObject("SELECT test_evidence FROM tp_credential WHERE id = ?",
                String.class, credentialId);
    }

    private static CredentialTestInput withHeader(long id, String url, String method, String body,
                                                  String headerName, String headerValue) {
        return new CredentialTestInput(url, method, body,
                List.of(new CredentialTestHeaderInput(headerName, headerValue)));
    }

    /** 2xx + a real vault reference through the header area -> VERIFIED + stamp. */
    @Test
    void injectedTwoHundredNonShellIsVerified() {
        long id = newCredential("verified");
        String credKey = jdbc.queryForObject("SELECT cred_key FROM tp_credential WHERE id = ?",
                String.class, id);
        CredentialTestOutcome outcome = testService.test(id, withHeader(id, base + "/biz", "POST",
                "{\"noop\":1}", "Authorization", "Bearer {{CRED:" + credKey + "}}"));
        assertEquals("VERIFIED", outcome.grade());
        assertEquals(1, outcome.injections());
        assertEquals("VERIFIED", evidenceOf(id));
        assertEquals("Bearer s3cr3t-VALUE-9f2d", AUTH_SEEN.get());
        assertNotNull(jdbc.queryForObject(
                "SELECT last_verified_at FROM tp_credential WHERE id = ?",
                java.sql.Timestamp.class, id));
    }

    /** Zero references on a 2xx -> UNVERIFIED (gray), never shown as passed. */
    @Test
    void zeroInjectionTwoHundredIsUnverified() {
        long id = newCredential("unverified");
        CredentialTestOutcome outcome = testService.test(id,
                new CredentialTestInput(base + "/biz", "POST", "{\"noop\":1}", List.of()));
        assertEquals("UNVERIFIED", outcome.grade());
        assertEquals(0, outcome.injections());
        assertEquals("UNVERIFIED", evidenceOf(id));
        assertNull(jdbc.queryForObject(
                "SELECT last_verified_at FROM tp_credential WHERE id = ?",
                java.sql.Timestamp.class, id));
        assertTrue(outcome.note().contains("用此凭据"));
    }

    /** 200 login-shell page with a valid reference -> UNKNOWN, not EXPIRED (no overrule). */
    @Test
    void twoHundredLoginShellDegradesToUnknown() {
        long id = newCredential("shell");
        String credKey = jdbc.queryForObject("SELECT cred_key FROM tp_credential WHERE id = ?",
                String.class, id);
        CredentialTestOutcome outcome = testService.test(id, withHeader(id, base + "/shell", "GET",
                "", "Authorization", "Bearer {{CRED:" + credKey + "}}"));
        assertEquals("UNKNOWN", outcome.grade());
        assertEquals("UNKNOWN", evidenceOf(id));
        assertTrue(outcome.note().contains("登录页"));
    }

    /** Knob off: the fingerprint stops degrading 2xx answers (honest fallback). */
    @Test
    void fingerprintKnobOffRestoresPlainTwoHundredVerdict() {
        Long knobId = jdbc.queryForObject(
                "SELECT id FROM tp_sys_config WHERE config_key = ?", Long.class, FINGERPRINT_KNOB);
        String group = jdbc.queryForObject(
                "SELECT group_name FROM tp_sys_config WHERE id = ?", String.class, knobId);
        String type = jdbc.queryForObject(
                "SELECT value_type FROM tp_sys_config WHERE id = ?", String.class, knobId);
        String desc = jdbc.queryForObject(
                "SELECT description FROM tp_sys_config WHERE id = ?", String.class, knobId);
        long id = newCredential("knoboff");
        String credKey = jdbc.queryForObject("SELECT cred_key FROM tp_credential WHERE id = ?",
                String.class, id);
        CredentialTestInput input = withHeader(id, base + "/shell", "GET", "",
                "Authorization", "Bearer {{CRED:" + credKey + "}}");
        try {
            configService.update(1L, knobId, new ConfigInput(FINGERPRINT_KNOB, "0",
                    group, type, 0, desc));
            assertEquals("VERIFIED", testService.test(id, input).grade());
        } finally {
            configService.update(1L, knobId, new ConfigInput(FINGERPRINT_KNOB, "1",
                    group, type, 0, desc));
            assertEquals("UNKNOWN", testService.test(id, input).grade());
        }
    }

    /** 401 with a reference -> EXPIRED (red), stamp column follows. */
    @Test
    void rejectedRequestIsExpired() {
        long id = newCredential("expired");
        String credKey = jdbc.queryForObject("SELECT cred_key FROM tp_credential WHERE id = ?",
                String.class, id);
        CredentialTestOutcome outcome = testService.test(id, withHeader(id, base + "/deny", "POST",
                "{\"noop\":1}", "Authorization", "Bearer {{CRED:" + credKey + "}}"));
        assertEquals("EXPIRED", outcome.grade());
        assertEquals("EXPIRED", evidenceOf(id));
    }

    /** Three-surface count: URL + header + body references all reconcile. */
    @Test
    void injectionsCountUrlHeadersAndBodyTogether() {
        long id = newCredential("threeface");
        String credKey = jdbc.queryForObject("SELECT cred_key FROM tp_credential WHERE id = ?",
                String.class, id);
        CredentialTestInput input = new CredentialTestInput(
                base + "/track/{{CRED:" + credKey + "}}", "POST",
                "{\"a\":\"{{CRED:" + credKey + "}}\"}",
                List.of(new CredentialTestHeaderInput("X-Token-1", "{{CRED:" + credKey + "}}"),
                        new CredentialTestHeaderInput("X-Token-2", "prefix-{{CRED:"
                                + credKey + "}}-suffix")));
        CredentialTestOutcome outcome = testService.test(id, input);
        assertEquals(4, outcome.injections());
        assertEquals("VERIFIED", outcome.grade());
        assertEquals("/track/s3cr3t-VALUE-9f2d", PATH_SEEN.get());
    }

    /** A reference to a foreign credKey is honestly refused before sending. */
    @Test
    void foreignCredentialReferenceFailsClosed() {
        long id = newCredential("foreign");
        CredentialTestOutcome outcome = testService.test(id, withHeader(id, base + "/biz", "POST",
                "{\"noop\":1}", "Authorization", "Bearer {{CRED:not_the_row_key}}"));
        assertEquals("FAILED", outcome.verdict());
        assertNull(outcome.grade());
    }
}
