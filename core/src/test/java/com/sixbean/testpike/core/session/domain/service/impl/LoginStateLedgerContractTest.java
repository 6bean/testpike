package com.sixbean.testpike.core.session.domain.service.impl;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.session.api.dto.LoginStateInfo;
import com.sixbean.testpike.core.session.api.dto.LoginStateInput;
import com.sixbean.testpike.core.session.domain.service.LoginStateService;
import com.sixbean.testpike.core.session.SessionJwtFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Login-state ledger contract tests : column contract against the
 * real slot6 schema, legacy tp_credential zero-drift, ledger register/renew
 * semantics (D9: same-name re-capture updates, never appends) and verification
 * write-back. Real database, no mocks.
 */
@SpringBootTest(classes = CoreApplication.class)
class LoginStateLedgerContractTest {

    @Autowired
    private LoginStateService loginStateService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;

    @BeforeEach
    void setUp() {
        String code = "lsd" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "session-ledger-" + code, code);
        projectId = jdbcTemplate.queryForObject("SELECT id FROM tp_project WHERE code = ?",
                Long.class, code);
        cleanRows();
    }

    @AfterEach
    void tearDown() {
        cleanRows();
        jdbcTemplate.update("DELETE FROM tp_credential WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
    }

    private void cleanRows() {
        if (projectId != 0) {
            jdbcTemplate.update("DELETE FROM tp_login_state WHERE project_id = ?", projectId);
        }
    }

    private static String jwtWithExp(long epochSeconds) {
        return SessionJwtFixture.signedWithExp(epochSeconds);
    }

    // ---- column contract against the migrated schema ----

    @Test
    void tpLoginStateColumnsMatchLedgerContract() {
        Set<String> columns = Set.copyOf(jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns"
                        + " WHERE table_name = 'tp_login_state'", String.class));
        assertEquals(Set.of("id", "project_id", "state_name", "role_scope", "account_label",
                "kind", "credential_id", "state_object_key", "scope_origins", "extra_auth_refs",
                "source_kind",
                "expires_at", "last_verified_at", "status", "remark", "created_at", "updated_at"),
                columns);
    }

    @Test
    void credentialLifecycleColumnsExistAndAreNullable() {
        List<String> columns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns"
                        + " WHERE table_name = 'tp_credential' AND column_name IN"
                        + " ('source_kind','expires_at','last_verified_at')", String.class);
        assertEquals(3, columns.size());
        Integer notNull = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_name = 'tp_credential'"
                        + " AND column_name IN ('source_kind','expires_at','last_verified_at')"
                        + " AND is_nullable = 'NO'", Integer.class);
        assertEquals(0, notNull, "lifecycle columns must stay nullable for legacy rows");
    }

    @Test
    void legacyCredentialRowWithoutLifecycleColumnsStillReads() {
        jdbcTemplate.update("INSERT INTO tp_credential (project_id, name, cred_key, secret_cipher,"
                        + " status, created_at, updated_at) VALUES (?, ?, ?, ?, 1, now(), now())",
                projectId, "legacy", "legacy_key", "ENC:legacy-cipher");
        String masked = jdbcTemplate.queryForObject(
                "SELECT secret_cipher FROM tp_credential WHERE project_id = ? AND cred_key = 'legacy_key'",
                String.class, projectId);
        assertEquals("ENC:legacy-cipher", masked);
        Integer sourceKind = jdbcTemplate.queryForObject(
                "SELECT source_kind FROM tp_credential WHERE project_id = ? AND cred_key = 'legacy_key'",
                Integer.class, projectId);
        assertNull(sourceKind, "legacy rows keep NULL provenance");
    }

    // ---- ledger register / renew semantics ----

    private LoginStateInput input(String stateName, String role, String label,
                                  String kind, String credKey, String secret) {
        return new LoginStateInput(stateName, role, label, kind, credKey, secret,
                null, List.of("https://app.test"), null);
    }

    @Test
    void registerStoresCredentialAndLedgerRowAndParsesExpiry() {
        long exp = Instant.now().getEpochSecond() + 3600;
        long id = loginStateService.register(projectId,
                input("approver-a", "approver", "A", "token", "sess_a", jwtWithExp(exp)));
        LoginStateInfo info = loginStateService.get(id);
        assertEquals("approver-a", info.stateName());
        assertNotNull(info.credentialId());
        assertEquals("sess_a", info.credKey());
        assertNotNull(info.expiresAt(), "a parseable expiry must be stored by the service layer");
        assertEquals(List.of("https://app.test"), info.scopeOrigins());
        assertEquals(3, info.sourceKind());
    }

    @Test
    void registerWithUnparseableSecretStoresHonestNullExpiry() {
        long id = loginStateService.register(projectId,
                input("cookie-row", "runner", null, "cookie_set", "sess_c", "SESSIONID=x; Path=/"));
        assertNull(loginStateService.get(id).expiresAt(), "unparseable = honest NULL, never a guess");
    }

    @Test
    void sameNameReCaptureUpdatesRowInPlaceAndRenewsSecret() {
        long first = loginStateService.register(projectId,
                input("approver-a", "approver", "A", "token", "sess_a",
                        jwtWithExp(Instant.now().getEpochSecond() + 60)));
        long second = loginStateService.register(projectId,
                input("approver-a", "approver", "A2", "token", "sess_a2",
                        jwtWithExp(Instant.now().getEpochSecond() + 7200)));
        assertEquals(first, second, "re-capture renews the row, never appends (D9)");
        Integer rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tp_login_state WHERE project_id = ?", Integer.class, projectId);
        assertEquals(1, rowCount);
        LoginStateInfo info = loginStateService.get(first);
        assertEquals("A2", info.accountLabel());
        assertEquals("sess_a2", info.credKey());
        String renewedKey = jdbcTemplate.queryForObject(
                "SELECT cred_key FROM tp_credential WHERE id = ?", String.class, info.credentialId());
        assertEquals("sess_a2", renewedKey);
    }

    @Test
    void twoAccountsCoexistWithIndependentRows() {
        long a = loginStateService.register(projectId,
                input("acct-a", "approver", "A", "token", "sa",
                        jwtWithExp(Instant.now().getEpochSecond() + 60)));
        long b = loginStateService.register(projectId,
                input("acct-b", "approver", "B", "token", "sb",
                        jwtWithExp(Instant.now().getEpochSecond() + 86400)));
        assertTrue(a != b);
        assertEquals(2, loginStateService.list(projectId, null).size());
        assertNotNull(loginStateService.get(a).expiresAt());
        assertNotNull(loginStateService.get(b).expiresAt());
    }

    @Test
    void registerRejectsInvalidKindAndBlankStateName() {
        assertThrows(ServiceException.class, () -> loginStateService.register(projectId,
                input("bad-kind", "r", null, "sms_code", "sk", "v")));
        assertThrows(ServiceException.class, () -> loginStateService.register(projectId,
                input("  ", "r", null, "token", "sk", "v")));
    }

    // ---- status / verification write-back ----

    @Test
    void markVerifiedSetsTimestampAndFailureDisables() {
        long id = loginStateService.register(projectId,
                input("vrow", "runner", null, "token", "sv",
                        jwtWithExp(Instant.now().getEpochSecond() + 60)));
        loginStateService.markVerified(id, true);
        Timestamp at = jdbcTemplate.queryForObject(
                "SELECT last_verified_at FROM tp_login_state WHERE id = ?", Timestamp.class, id);
        assertNotNull(at);
        assertEquals(1, loginStateService.get(id).status());
        loginStateService.markVerified(id, false);
        assertEquals(0, loginStateService.get(id).status(), "a failed check marks the row invalid");
        assertEquals(0, loginStateService.list(projectId, true).size(),
                "disabled rows leave the enabled-only listing");
    }

    @Test
    void setEnabledAndDeleteManageLedgerRow() {
        long id = loginStateService.register(projectId,
                input("drow", "runner", null, "token", "sd",
                        jwtWithExp(Instant.now().getEpochSecond() + 60)));
        loginStateService.setEnabled(id, false);
        assertEquals(0, loginStateService.get(id).status());
        loginStateService.setEnabled(id, true);
        assertEquals(1, loginStateService.get(id).status());
        loginStateService.delete(id);
        assertThrows(ServiceException.class, () -> loginStateService.get(id));
        assertEquals(0, loginStateService.list(projectId, null).size());
    }

    @Test
    void getUnknownIdFailsClosed() {
        assertThrows(ServiceException.class, () -> loginStateService.get(999_999_999L));
    }
}
