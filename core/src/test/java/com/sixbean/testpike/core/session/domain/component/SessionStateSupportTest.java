package com.sixbean.testpike.core.session.domain.component;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.credential.api.dto.CredentialInput;
import com.sixbean.testpike.core.credential.domain.service.CredentialService;
import com.sixbean.testpike.core.session.infrastructure.dataobject.LoginState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SessionStateSupport contract tests ( S1 single point: state metadata,
 * consumption resolution, expiry parsing). Real slot6 database, no mocks.
 * Expiry parsing covers the three declared shapes each in both directions:
 * JWT exp / cookie Expires / unparseable-null.
 */
@SpringBootTest(classes = CoreApplication.class)
class SessionStateSupportTest {

    @Autowired
    private SessionStateSupport support;

    @Autowired
    private CredentialService credentialService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;

    @BeforeEach
    void setUp() {
        String code = "sst" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "session-support-" + code, code);
        projectId = jdbcTemplate.queryForObject("SELECT id FROM tp_project WHERE code = ?",
                Long.class, code);
        cleanStates();
    }

    @AfterEach
    void tearDown() {
        cleanStates();
        jdbcTemplate.update("DELETE FROM tp_credential WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
    }

    private void cleanStates() {
        if (projectId != 0) {
            jdbcTemplate.update("DELETE FROM tp_login_state WHERE project_id = ?", projectId);
        }
    }

    private static String base64Url(String raw) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static String jwtWithExp(long epochSeconds) {
        return base64Url("{\"alg\":\"HS256\",\"typ\":\"JWT\"}") + "."
                + base64Url("{\"sub\":\"u1\",\"exp\":" + epochSeconds + "}") + ".c2ln";
    }

    private LocalDateTime expectedFromEpoch(long epochSeconds) {
        return LocalDateTime.ofInstant(Instant.ofEpochSecond(epochSeconds), ZoneId.systemDefault());
    }

    // ---- expiry parsing: JWT exp ----

    @Test
    void parseExpiresAtReadsFutureJwtExpClaim() {
        long exp = Instant.now().getEpochSecond() + 3600;
        LocalDateTime parsed = support.parseExpiresAt("token", jwtWithExp(exp));
        assertNotNull(parsed);
        assertTrue(Duration.between(parsed, expectedFromEpoch(exp)).abs().getSeconds() <= 1);
    }

    @Test
    void parseExpiresAtReadsPastJwtExpClaimForHonestExpiredState() {
        long exp = Instant.now().getEpochSecond() - 120;
        LocalDateTime parsed = support.parseExpiresAt("token", jwtWithExp(exp));
        assertNotNull(parsed, "an expired claim is still a parsed expiry, not an unknown");
        assertTrue(parsed.isBefore(LocalDateTime.now()));
    }

    @Test
    void parseExpiresAtReturnsNullForMalformedJwtShapes() {
        assertNull(support.parseExpiresAt("token", "not-a-jwt"));
        assertNull(support.parseExpiresAt("token", "a.b"));
        assertNull(support.parseExpiresAt("token", "eyJhbGciOiJIUzI1NiJ9.bm90anNvbg.sig"));
        assertNull(support.parseExpiresAt("token", base64Url("{\"sub\":\"u1\"}") + "..s"));
        assertNull(support.parseExpiresAt("token", base64Url("{\"exp\":\"soon\"}") + ".x.y"));
        assertNull(support.parseExpiresAt("token", null));
        assertNull(support.parseExpiresAt("token", ""));
    }

    // ---- expiry parsing: cookie Expires ----

    @Test
    void parseExpiresAtReadsCookieExpiresAttribute() {
        String raw = "SESSIONID=abc; Path=/; Expires=Wed, 09 Jun 2027 10:18:14 GMT";
        LocalDateTime parsed = support.parseExpiresAt("cookie_set", raw);
        assertNotNull(parsed);
        LocalDateTime expected = ZonedDateTime
                .parse("Wed, 09 Jun 2027 10:18:14 GMT", DateTimeFormatter.RFC_1123_DATE_TIME)
                .withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
        assertEquals(expected, parsed);
    }

    @Test
    void parseExpiresAtReadsPastCookieExpiresAttribute() {
        String raw = "SESSIONID=abc; Expires=Wed, 09 Jun 2021 10:18:14 GMT";
        LocalDateTime parsed = support.parseExpiresAt("cookie_set", raw);
        assertNotNull(parsed);
        assertTrue(parsed.isBefore(LocalDateTime.now()));
    }

    @Test
    void parseExpiresAtReturnsNullForMalformedCookieAndOtherKinds() {
        assertNull(support.parseExpiresAt("cookie_set", "SESSIONID=abc; Path=/"));
        assertNull(support.parseExpiresAt("cookie_set", "SESSIONID=abc; Expires=nonsense"));
        assertNull(support.parseExpiresAt("storage_state", "{\"cookies\":[]}"));
        assertNull(support.parseExpiresAt("bogus_kind", jwtWithExp(Instant.now().getEpochSecond() + 60)));
    }

    // ---- expiry parsing: captured commit entries shape (AV capture lineage) ----

    /**
     * The commit path stores the checked subset as a JSON array of entries
     * ({@code expiresAtEpochSecond} per row, null for session-only values),
     * while manual uploads carry the Playwright object — the ledger expiry
     * must parse from both shapes, earliest positive value wins.
     */
    @Test
    void parseExpiresAtReadsCaptureEntriesArrayEarliestExpiry() {
        long first = Instant.now().getEpochSecond() + 3600;
        long second = Instant.now().getEpochSecond() + 7200;
        String entries = "[{\"kind\":\"cookie\",\"name\":\"sid\",\"value\":\"x\","
                + "\"origin\":\"http://127.0.0.1:8899\",\"expiresAtEpochSecond\":" + second + "},"
                + "{\"kind\":\"localStorage\",\"name\":\"plain\",\"value\":\"z\","
                + "\"origin\":\"http://127.0.0.1:8899\",\"expiresAtEpochSecond\":null},"
                + "{\"kind\":\"cookie\",\"name\":\"access_token\",\"value\":\"y\","
                + "\"origin\":\"http://127.0.0.1:8899\",\"expiresAtEpochSecond\":" + first + "}]";
        LocalDateTime parsed = support.parseExpiresAt("storage_state", entries);
        assertNotNull(parsed);
        assertEquals(LocalDateTime.ofInstant(Instant.ofEpochSecond(first), ZoneId.systemDefault()), parsed);
        assertNotNull(support.parseExpiresAt("cookie_set", entries));
    }

    @Test
    void parseExpiresAtKeepsHonestUnknownForSessionOnlyCaptureEntries() {
        String entries = "[{\"kind\":\"localStorage\",\"name\":\"sid\",\"value\":\"x\","
                + "\"origin\":\"http://127.0.0.1:8899\",\"expiresAtEpochSecond\":-1}]";
        assertNull(support.parseExpiresAt("storage_state", entries));
        assertNull(support.parseExpiresAt("storage_state", "[not-json"));
    }

    // ---- expiry state computation (state light inputs) ----

    @Test
    void expiryStateCoversFourStatesInBothDirections() {
        LocalDateTime now = LocalDateTime.now();
        assertEquals(ExpiryState.UNKNOWN, support.expiryState(null, now, 15));
        assertEquals(ExpiryState.EXPIRED, support.expiryState(now.minusMinutes(1), now, 15));
        assertEquals(ExpiryState.EXPIRING, support.expiryState(now.plusMinutes(5), now, 15));
        assertEquals(ExpiryState.EXPIRING, support.expiryState(now.plusMinutes(15), now, 15));
        assertEquals(ExpiryState.VALID, support.expiryState(now.plusMinutes(16), now, 15));
    }

    // ---- origin whitelist (D11 fail-closed) ----

    @Test
    void originAllowedMatchesWhitelistExactlyAndRefusesOutside() {
        List<String> origins = List.of("https://app.test", "https://idp.test");
        assertTrue(support.originAllowed(origins, "https://app.test"));
        assertTrue(support.originAllowed(origins, "https://idp.test/"), "trailing slash is the same origin");
        assertFalse(support.originAllowed(origins, "https://evil.test"));
        assertFalse(support.originAllowed(List.of(), "https://app.test"),
                "an empty whitelist reads nothing (fail-closed)");
        assertFalse(support.originAllowed(null, "https://app.test"));
    }

    @Test
    void originAllowedTreatsPortlessCookieOriginAsHostScopedMatch() {
        // RFC 6265 cookies carry no port; the executor emits scheme://host for
        // cookie candidates. A scope entry on a non-default port must still
        // cover them, otherwise every captured cookie is server-side refused.
        List<String> scope = List.of("http://127.0.0.1:8899", "https://login.example:8443");
        assertTrue(support.originAllowed(scope, "http://127.0.0.1"),
                "portless cookie origin matches the host-scoped scope entry");
        assertTrue(support.originAllowed(scope, "https://login.example"),
                "secure cookie origin matches the https host-scoped entry");
        assertFalse(support.originAllowed(scope, "https://127.0.0.1"),
                "scheme must match exactly even when the host is covered");
        assertFalse(support.originAllowed(scope, "http://127.0.0.1:9000"),
                "an explicit non-default port stays strict: 9000 is not 8899");
        assertFalse(support.originAllowed(scope, "http://other.test"),
                "hosts outside the whitelist stay refused (fail-closed)");
    }

    @Test
    void originAllowedNormalizesDefaultPortsAndRefusesUnparseable() {
        List<String> scope = List.of("http://127.0.0.1:8899");
        assertTrue(support.originAllowed(scope, "http://127.0.0.1:80"),
                "port 80 is the http default and equals the portless origin");
        assertFalse(support.originAllowed(scope, "not a url"),
                "an unparseable origin is refused, never matched by substring");
        assertFalse(support.originAllowed(scope, "javascript:alert(1)"),
                "non-web schemes are outside the http(s) contract");
    }

    // ---- resolution (D9 four rulings) ----

    private long insertState(String stateName, String roleScope, String accountLabel,
                             String kind, long credentialId, int status, String origins) {
        jdbcTemplate.update("INSERT INTO tp_login_state (project_id, state_name, role_scope,"
                        + " account_label, kind, credential_id, scope_origins, status)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?)",
                projectId, stateName, roleScope, accountLabel, kind, credentialId, origins, status);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM tp_login_state WHERE project_id = ? AND state_name = ?",
                Long.class, projectId, stateName);
    }

    private long newCredential(String credKey, String secret) {
        return credentialService.create(projectId, new CredentialInput(credKey, credKey, secret, null));
    }

    @Test
    void resolveByExplicitStateNameTakesTheExactRowEvenWhenRoleAmbiguous() {
        long cred = newCredential("sess_a", "v");
        insertState("approver-a", "approver", "A", "token", cred, 1, "[\"https://app.test\"]");
        insertState("approver-b", "approver", "B", "token", cred, 1, "[\"https://app.test\"]");
        LoginState row = support.resolve(projectId, "approver-b", null);
        assertEquals("approver-b", row.getStateName());
    }

    @Test
    void resolveByRoleFailsClosedListingCandidatesWhenMultiRow() {
        long cred = newCredential("sess_r", "v");
        insertState("acct-one", "approver", "A", "token", cred, 1, "[]");
        insertState("acct-two", "approver", "B", "token", cred, 1, "[]");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> support.resolve(projectId, null, "approver"));
        assertTrue(ex.getMessage().contains("acct-one") && ex.getMessage().contains("acct-two"),
                "fail-closed message must name the role and enumerate candidates: " + ex.getMessage());
    }

    @Test
    void resolveByRoleSucceedsWhenSingleRowAndRejectsMissing() {
        long cred = newCredential("sess_s", "v");
        insertState("only-one", "runner", null, "cookie_set", cred, 1, "[]");
        assertEquals("only-one", support.resolve(projectId, null, "runner").getStateName());
        assertThrows(ServiceException.class, () -> support.resolve(projectId, null, "nobody"));
        assertThrows(ServiceException.class, () -> support.resolve(projectId, "no-such-name", null));
        assertThrows(ServiceException.class, () -> support.resolve(projectId, null, null));
    }

    @Test
    void resolveRefusesDisabledRowForConsumption() {
        long cred = newCredential("sess_d", "v");
        insertState("paused", "runner", null, "token", cred, 0, "[]");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> support.resolve(projectId, "paused", null));
        assertTrue(ex.getMessage().contains("paused"), "name the refused row: " + ex.getMessage());
    }

    @Test
    void revealStateValueReturnsPlaintextFromSingleVault() {
        String secret = jwtWithExp(Instant.now().getEpochSecond() + 600);
        long cred = newCredential("sess_v", secret);
        insertState("vault-row", "runner", null, "token", cred, 1, "[]");
        assertEquals(secret, support.revealStateValue(projectId, "vault-row"));
    }

    @Test
    void storageStateSnapshotExpiresAtEarliestPositiveCookie() {
        // -D: a captured storage_state carries per-cookie `expires`
        // (epoch seconds, -1 for session-only). The ledger row must show the
        // earliest positive instant, not UNKNOWN, when one exists.
        long soon = Instant.now().getEpochSecond() + 120;
        long far = Instant.now().getEpochSecond() + 86_400;
        String snapshot = "{\"cookies\":["
                + "{\"name\":\"s\",\"value\":\"v\",\"expires\":-1},"
                + "{\"name\":\"a\",\"value\":\"v\",\"expires\":" + far + "},"
                + "{\"name\":\"b\",\"value\":\"v\",\"expires\":" + soon + "}],"
                + "\"origins\":[]}";
        LocalDateTime parsed = support.parseExpiresAt("storage_state", snapshot);
        assertNotNull(parsed, "storage_state 快照必须解析出 cookie 最早正过期时间");
        assertEquals(LocalDateTime.ofInstant(Instant.ofEpochSecond(soon), ZoneId.systemDefault()),
                parsed, "多 cookie 取最小正过期（最先到期的凭证决定会话寿命）");

        // Honest UNKNOWN shapes: no positive instant, or unparseable payload.
        assertNull(support.parseExpiresAt("storage_state", "{\"cookies\":[{\"expires\":-1}],\"origins\":[]}"),
                "全为会话级 cookie 时不得猜测过期时间");
        assertNull(support.parseExpiresAt("storage_state", "{\"cookies\":[]}"));
        assertNull(support.parseExpiresAt("storage_state", "not-json"));
    }
}
