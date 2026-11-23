package com.sixbean.testpike.core.credential.domain.service.impl;

import com.sun.net.httpserver.HttpServer;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.credential.api.dto.CredentialInput;
import com.sixbean.testpike.core.credential.api.dto.CredentialTestInput;
import com.sixbean.testpike.core.credential.domain.service.CredentialService;
import com.sixbean.testpike.core.credential.domain.service.CredentialTestService;
import com.sixbean.testpike.core.credential.api.dto.CredentialTestOutcome;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L2 contract for the credential vault "test now" door.
 *
 * <p>Everything is real: vault encryption, the reveal audit path, a loopback
 * JDK server standing in for the system-under-test (it records the true
 * request body), and the shared event ledger. The two halves of the user
 * promise are asserted together - the wire must carry the real material
 * while the trail must never show it.
 */
@SpringBootTest(classes = CoreApplication.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CredentialTestServiceL2Test {

    private static long project;
    private static final String CRED_KEY = "probe_tail_vault";
    private static final String SECRET = "user1:pw-S3CR3T-4d7c";

    private static HttpServer server;
    private static String base;
    private static final AtomicReference<String> RECEIVED_BODY = new AtomicReference<>("");

    @Autowired CredentialTestService testService;
    @Autowired CredentialService credentialService;
    @Autowired JdbcTemplate jdbc;

    private static long credentialId;

    @BeforeAll
    void setUp() throws Exception {
        project = jdbc.queryForObject(
                "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1) RETURNING id",
                Long.class, "tail-vault-" + System.nanoTime(), "tvl" + System.nanoTime());
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/login-ok", exchange -> {
            RECEIVED_BODY.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"token\":\"issued\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/login-deny", exchange -> {
            RECEIVED_BODY.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        credentialId = credentialService.create(project,
                new CredentialInput("尾流测试凭据", CRED_KEY, SECRET, "L2 契约行"));
    }

    @AfterAll
    void cleanUp() {
        if (server != null) {
            server.stop(0);
        }
        jdbc.update("DELETE FROM tp_ai_task_event WHERE job_kind = 'credential-test'"
                + " AND job_ref IN (SELECT CAST(id AS VARCHAR) FROM tp_credential WHERE project_id = ?)",
                project);
        jdbc.update("DELETE FROM tp_login_state WHERE project_id = ?", project);
        jdbc.update("DELETE FROM tp_credential WHERE project_id = ?", project);
        jdbc.update("DELETE FROM tp_project WHERE id = ?", project);
    }

    private List<Map<String, Object>> events(String runToken) {
        return jdbc.queryForList("SELECT level, summary, detail FROM tp_ai_task_event"
                + " WHERE job_kind = 'credential-test' AND run_token = ? ORDER BY id", runToken);
    }

    @Test
    void acceptedLoginCarriesRealSecretOnTheWireButOnlyTemplateInTheTrail() {
        CredentialTestOutcome result = testService.test(credentialId, new CredentialTestInput(
                base + "/login-ok", "POST",
                "{\"user\":\"tester\",\"pass\":\"{{CRED:" + CRED_KEY + "}}\"}"));
        assertEquals("VALID", result.verdict());

        assertTrue(RECEIVED_BODY.get().contains(SECRET),
                "the injected request must carry the true vault material server-side");

        String all = events(result.runToken()).stream()
                .map(r -> r.get("summary") + "\n" + r.get("detail")).reduce("", (a, b) -> a + b);
        assertTrue(all.contains(base + "/login-ok"), "the target address must be readable");
        assertTrue(all.contains("{{CRED:" + CRED_KEY + "}}"), "the trail shows the template verbatim");
        assertFalse(all.contains("pw-S3CR3T-4d7c"), "the vault material must NEVER reach the trail");
        assertTrue(all.contains("HTTP 200"), "the server answer rides the trail");
        assertTrue(all.contains("token"), "response body preview rides the trail");
        assertTrue(all.contains("1 处 {{CRED:" + CRED_KEY + "}} 占位符"), "the injection count names the real key");
        assertNotNull(jdbc.queryForObject(
                "SELECT last_verified_at FROM tp_credential WHERE id = ?", java.sql.Timestamp.class, credentialId),
                "an accepted answer stamps the verification clock");
    }

    @Test
    void deniedLoginVerdictsExpiredAndKeepsTheClock() {
        jdbc.update("UPDATE tp_credential SET last_verified_at = NULL WHERE id = ?", credentialId);
        CredentialTestOutcome result = testService.test(credentialId, new CredentialTestInput(
                base + "/login-deny", "POST", "{\"pass\":\"{{CRED:" + CRED_KEY + "}}\"}"));
        assertEquals("EXPIRED", result.verdict());
        String all = events(result.runToken()).stream()
                .map(r -> r.get("summary") + " " + r.get("detail")).reduce("", (a, b) -> a + b);
        assertTrue(all.contains("HTTP 401"));
        assertTrue(all.contains("口令"), "the wording points at the credential question");
        assertNull(jdbc.queryForObject(
                "SELECT last_verified_at FROM tp_credential WHERE id = ?", java.sql.Timestamp.class, credentialId));
    }

    @Test
    void noTargetNoBindingFailsWithPointingWording() {
        // a fresh credential with nothing bound: blank url must name the door out
        long lonely = credentialService.create(project,
                new CredentialInput("孤行凭据", "probe_tail_lonely", "x:y", "L2 契约行"));
        try {
            CredentialTestOutcome result = testService.test(lonely, new CredentialTestInput(null, "GET", null));
            assertEquals("FAILED", result.verdict());
            String all = events(result.runToken()).stream()
                    .map(r -> r.get("summary") + " " + r.get("detail")).reduce("", (a, b) -> a + b);
            assertTrue(all.contains("找不到验证目标地址"), "honest refusal names the input problem");
            assertTrue(all.contains("目标系统"), "and the remedy path");
        } finally {
            jdbc.update("DELETE FROM tp_credential WHERE id = ?", lonely);
        }
    }

    @Test
    void boundLoginStateSuppliesTheDefaultTarget() {
        // ledger row bound to this credential: its first origin pre-fills the run
        jdbc.update("INSERT INTO tp_login_state (project_id, state_name, role_scope, account_label,"
                + " kind, credential_id, scope_origins, source_kind, status, remark)"
                + " VALUES (?, 'tail_bound_state', 'probe', '绑定账号', 'cookie_set', ?,"
                + " CAST(? AS jsonb), 3, 1, 'L2 契约行')",
                project, credentialId, "[\"" + base + "/login-ok\"]");
        Long boundId = jdbc.queryForObject(
                "SELECT id FROM tp_login_state WHERE project_id = ? AND state_name = 'tail_bound_state'",
                Long.class, project);
        assertNotNull(boundId);
        CredentialTestOutcome result = testService.test(credentialId,
                new CredentialTestInput(null, "GET", null));
        assertEquals("VALID", result.verdict(),
                "GET against login-ok via the bound origin should read 200");
        String all = events(result.runToken()).stream()
                .map(r -> r.get("summary") + " " + r.get("detail")).reduce("", (a, b) -> a + b);
        assertTrue(all.contains("绑定的登录态账本作用源"), "the trail states where the address came from");
        jdbc.update("DELETE FROM tp_login_state WHERE id = ?", boundId);
    }
}
