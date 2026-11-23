package com.sixbean.testpike.core.session.domain.service.impl;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.session.SessionJwtFixture;
import com.sixbean.testpike.core.session.api.dto.ExtraAuthRef;
import com.sixbean.testpike.core.session.api.dto.LoginStateInfo;
import com.sixbean.testpike.core.session.api.dto.LoginStateInput;
import com.sixbean.testpike.core.session.domain.service.LoginStateService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Login-state edit door (ledger row editing, the dialog promise
 * completed): a registered state can be renamed, re-typed, re-scoped, have
 * its reference key re-pointed and its combined-session header rows added,
 * replaced or cleared — everything the register dialog can build, the ledger
 * row can now edit.
 *
 * <p>Pinned contracts:
 * 1. metadata-only edits keep id, status, the verification stamp and the
 * vault ciphertext; an unchanged save is a true no-op (updated_at stays);
 * 2. renames must stay unique per project — a collision is refused naming
 * the taken anchor (the name is what consumers reference);
 * 3. a blank secret never touches the vault; supplied secret replaces the
 * ciphertext and re-parses the expiry;
 * 4. the verification stamp drops only when replay material changed (kind,
 * reference key, secret, or the combined-header list);
 * 5. an explicit header list replaces wholesale, a missing list keeps the
 * stored one, and the kept list is still validated against the NEW kind's
 * primary header (legacy direct callers cannot smuggle a clash);
 * 6. reference keys are never created implicitly without material.
 * Real database, no mocks.
 */
@SpringBootTest(classes = CoreApplication.class)
class LoginStateUpdateContractTest {

    @Autowired
    private LoginStateService loginStateService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;

    @BeforeEach
    void setUp() {
        String code = "lse" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "session-edit-" + code, code);
        projectId = jdbcTemplate.queryForObject("SELECT id FROM tp_project WHERE code = ?", Long.class, code);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM tp_login_state WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_credential WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
    }

    private static LoginStateInput input(String name, String kind, String credKey, String secret,
                                         List<String> origins, List<ExtraAuthRef> refs) {
        return new LoginStateInput(name, "approver", "账号甲", kind, credKey, secret, null,
                origins, "备注甲", refs);
    }

    private static ExtraAuthRef ref(String credKey, String header, String template) {
        return new ExtraAuthRef(credKey, ExtraAuthRef.INTO_HEADER, header, template);
    }

    private String cipherOf(long stateId) {
        return jdbcTemplate.queryForObject(
                "SELECT c.secret_cipher FROM tp_credential c"
                        + " JOIN tp_login_state l ON l.credential_id = c.id WHERE l.id = ?",
                String.class, stateId);
    }

    private Timestamp updatedAt(long stateId) {
        return jdbcTemplate.queryForObject("SELECT updated_at FROM tp_login_state WHERE id = ?",
                Timestamp.class, stateId);
    }

    // ------------------------------------------------------------------

    @Test
    void metadataOnlyEditKeepsIdStatusVerificationStampAndCipher() {
        long id = loginStateService.register(projectId, input("meta-keep", "token", "meta_keep",
                SessionJwtFixture.signedWithExp(Instant.now().getEpochSecond() + 86400),
                List.of("https://a.example.com"), null));
        jdbcTemplate.update("UPDATE tp_login_state SET last_verified_at = now() WHERE id = ?", id);
        String cipher = cipherOf(id);

        loginStateService.update(id, input("meta-keep", "token", "meta_keep", null,
                List.of("https://a.example.com", "https://b.example.com"),
                loginStateService.get(id).extraAuthRefs()));

        LoginStateInfo after = loginStateService.get(id);
        assertEquals(id, after.id(), "edit lands on the same row");
        assertEquals(Integer.valueOf(1), after.status(), "an edit never re-enables or disables");
        assertEquals(cipher, cipherOf(id), "blank secret never touches the vault ciphertext");
        assertNotNull(after.lastVerifiedAt(), "metadata-only edit keeps the verification stamp");
        assertEquals(2, after.scopeOrigins().size(), "site scope replaced as typed");
    }

    @Test
    void unchangedSaveIsANoOpWithoutTimestampChurn() {
        long id = loginStateService.register(projectId,
                input("noop-save", "token", "noop_save", "plain-secret", List.of(), null));
        Timestamp first = updatedAt(id);

        loginStateService.update(id, input("noop-save", "token", "noop_save", null,
                List.of(), List.of()));

        assertEquals(first, updatedAt(id), "an edit that changes nothing writes nothing");
    }

    @Test
    void renameCollisionIsRefusedNamingTheAnchor() {
        loginStateService.register(projectId, input("anchor-a", "token", "k1", "s1", List.of(), null));
        long second = loginStateService.register(projectId, input("anchor-b", "token", "k2", "s2", List.of(), null));

        ServiceException clash = assertThrows(ServiceException.class, () -> loginStateService.update(
                second, input("anchor-a", "token", "k2", null, List.of(), List.of())));
        assertTrue(clash.getMessage().contains("anchor-a"), "the taken name is named: " + clash.getMessage());
        assertTrue(clash.getMessage().contains("唯一"), "the rule is stated: " + clash.getMessage());
        assertEquals("anchor-b", loginStateService.get(second).stateName(), "refused rename changes nothing");

        loginStateService.update(second, input("anchor-c", "token", "k2", null, List.of(), List.of()));
        assertEquals("anchor-c", loginStateService.get(second).stateName(), "a free rename lands on the same row");
    }

    @Test
    void suppliedSecretReplacesCipherAndParsesFreshExpiry() {
        long id = loginStateService.register(projectId, input("renew-secret", "token", "renew_k",
                SessionJwtFixture.signedWithExp(Instant.now().getEpochSecond() - 60), List.of(), null));
        String oldCipher = cipherOf(id);
        assertNotNull(loginStateService.get(id).expiresAt(), "the past exp parses to a past expiry");
        assertTrue(loginStateService.get(id).expiresAt().isBefore(java.time.LocalDateTime.now()),
                "fixture row starts expired");
        assertNotNull(oldCipher);

        loginStateService.update(id, input("renew-secret", "token", "renew_k",
                SessionJwtFixture.signedWithExp(Instant.now().getEpochSecond() + 86400),
                List.of(), List.of()));

        LoginStateInfo after = loginStateService.get(id);
        assertNotEquals(oldCipher, cipherOf(id), "fresh material replaces the vault ciphertext");
        assertNotNull(after.expiresAt(), "expiry re-parsed from the new token");
        assertTrue(after.expiresAt().isAfter(java.time.LocalDateTime.now()), "the new expiry is in the future");
    }

    @Test
    void kindChangeDropsStampAndClashesWithTheNewPrimaryHeaderAreRefused() {
        long id = loginStateService.register(projectId, input("kind-switch", "cookie_set", "cookie_k",
                "raw-cookie=value", List.of(), List.of(ref("cookie_k", "X-Api-Key", null))));
        jdbcTemplate.update("UPDATE tp_login_state SET last_verified_at = now() WHERE id = ?", id);

        // Supplied list clashing with the NEW kind's primary header is refused.
        ServiceException clash = assertThrows(ServiceException.class, () -> loginStateService.update(
                id, input("kind-switch", "token", "cookie_k", null, List.of(),
                        List.of(ref("cookie_k", "Authorization", null)))));
        assertTrue(clash.getMessage().contains("主请求头同名"), "clash named: " + clash.getMessage());
        assertNotNull(loginStateService.get(id).lastVerifiedAt(), "refused edit changes nothing");

        // Legacy direct caller flipping the kind WITHOUT a list: the stored
        // list is validated against the new kind too — X-Api-Key does not
        // clash, so the edit lands and the stamp drops (material changed).
        loginStateService.update(id, new LoginStateInput("kind-switch", "approver", "账号甲", "token",
                "cookie_k", null, null, List.of(), "备注甲"));
        assertNull(loginStateService.get(id).lastVerifiedAt(), "kind change drops the verification stamp");
        assertEquals(1, loginStateService.get(id).extraAuthRefs().size(), "kept list survived the kind flip");

        // And a kept-list clash through the same door is refused.
        long authRow = loginStateService.register(projectId, input("keep-clash", "cookie_set", "cookie_k",
                null, List.of(), List.of(ref("cookie_k", "Authorization", null))));
        ServiceException keptClash = assertThrows(ServiceException.class,
                () -> loginStateService.update(authRow, new LoginStateInput("keep-clash", "approver",
                        "账号甲", "token", "cookie_k", null, null, List.of(), "备注甲")));
        assertTrue(keptClash.getMessage().contains("Authorization"),
                "the stored row is named even on the keep path: " + keptClash.getMessage());
    }

    @Test
    void combinedHeaderListReplaceKeepAndClearSemantics() {
        long id = loginStateService.register(projectId, input("refs-door", "token", "main_k", "secret",
                List.of(), List.of(ref("main_k", "X-First", "Bearer {{CRED:main_k}}"))));
        assertEquals(1, loginStateService.get(id).extraAuthRefs().size());

        loginStateService.update(id, input("refs-door", "token", "main_k", null, List.of(),
                List.of(ref("main_k", "X-First", null), ref("main_k", "X-Second", null))));
        assertEquals(2, loginStateService.get(id).extraAuthRefs().size(), "explicit list replaces wholesale");
        assertNull(loginStateService.get(id).lastVerifiedAt(), "ref-list change is a material change");

        loginStateService.update(id, new LoginStateInput("refs-door", "approver", "账号甲", "token",
                "main_k", null, null, List.of(), "备注乙"));
        assertEquals(2, loginStateService.get(id).extraAuthRefs().size(), "no list keeps the stored list");

        loginStateService.update(id, input("refs-door", "token", "main_k", null, List.of(), List.of()));
        assertEquals(0, loginStateService.get(id).extraAuthRefs().size(), "explicit empty list clears");
    }

    @Test
    void unregisteredReferenceKeyNeedsMaterialAndNeverGetsAnImplicitRow() {
        long id = loginStateService.register(projectId, input("relink", "token", "old_k", "s", List.of(), null));
        ServiceException missing = assertThrows(ServiceException.class, () -> loginStateService.update(
                id, input("relink", "token", "ghost_k", null, List.of(), List.of())));
        assertTrue(missing.getMessage().contains("ghost_k"), "the unknown key is named");
        assertTrue(missing.getMessage().contains("凭据保险箱"), "the remedy door is literal: " + missing.getMessage());
        assertEquals(0, countVault("ghost_k"), "refused re-point created no vault row");

        // With fresh material the reference key may create the vault row,
        // through the single encrypted path (never plaintext at rest).
        loginStateService.update(id, input("relink", "token", "ghost_k", "brand-new-secret", List.of(), List.of()));
        assertEquals(1, countVault("ghost_k"));
        LoginStateInfo after = loginStateService.get(id);
        assertEquals("ghost_k", after.credKey());
        String cipher = cipherOf(id);
        assertFalse(cipher.contains("brand-new-secret"), "plaintext never rests in the vault column");
    }

    @Test
    void editingAMissingRowIsHonestAndCaseDuplicatesInAListStillRefused() {
        ServiceException missing = assertThrows(ServiceException.class, () -> loginStateService.update(
                999999999L, input("ghost", "token", "k", null, List.of(), List.of())));
        assertTrue(missing.getMessage().contains("不存在"), missing.getMessage());

        long id = loginStateService.register(projectId, input("dup-name", "token", "dup_k", "s", List.of(), null));
        ServiceException dup = assertThrows(ServiceException.class, () -> loginStateService.update(
                id, input("dup-name", "token", "dup_k", null, List.of(),
                        List.of(ref("dup_k", "X-A", null), ref("dup_k", "x-a", null)))));
        assertTrue(dup.getMessage().contains("只允许一行"), "case-insensitive clash refused: " + dup.getMessage());
    }

    @Test
    void pastedTokenIntoBrowserKindIsRefusedAtSaveNamingBothDoors() {
        // 11-19 report shape: a raw JWT pasted into a cookie-kind row used to
        // fail only at probe time with a re-collect pointer that could never
        // succeed. The save door now refuses, naming both fix doors.
        String jwt = SessionJwtFixture.signedWithExp(Instant.now().getEpochSecond() + 86400);
        ServiceException refused = assertThrows(ServiceException.class, () -> loginStateService.register(
                projectId, input("pasted-jwt", "cookie_set", "jwt_k", jwt, List.of(), null)));
        assertTrue(refused.getMessage().contains("令牌文本"), "shape named: " + refused.getMessage());
        assertTrue(refused.getMessage().contains("改为 token"), "primary door named");
        assertTrue(refused.getMessage().contains("附加会话头"), "secondary door named");
        assertEquals(0, countVault("jwt_k"), "refused save wrote no vault row");

        // the same material is a first-class fit under the token kind
        long id = loginStateService.register(projectId,
                input("pasted-jwt", "token", "jwt_k", jwt, List.of(), null));
        assertEquals("token", loginStateService.get(id).kind());
    }

    @Test
    void cookieShapesStillRegisterUntouched() {
        loginStateService.register(projectId,
                input("cookie-kv", "cookie_set", "cv_k", "A=1; B=2", List.of(), null));
        loginStateService.register(projectId, input("cookie-array", "cookie_set", "arr_k",
                "[{\"kind\":\"cookie\",\"name\":\"S\",\"value\":\"v\",\"origin\":\"https://x.example.com\"}]",
                List.of(), null));
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_login_state WHERE project_id = ? AND kind = 'cookie_set'",
                Integer.class, projectId);
        assertEquals(2, rows, "recognized cookie shapes register exactly as before");
    }

    private int countVault(String credKey) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_credential WHERE project_id = ? AND cred_key = ?",
                Integer.class, projectId, credKey);
        return n == null ? 0 : n;
    }
}
