package com.sixbean.testpike.core.session.domain.service.impl;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.config.api.dto.ConfigInput;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
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

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Combined-session header list on the login-state ledger :
 * persistence round-trip, zero drift for the legacy nine-argument doors,
 * and the full fail-closed branch set at save time (unknown vault ref,
 * clash with the kind's primary header, in-list duplicates, non-header
 * destination, plaintext-bearing value template, hot size cap). Real
 * slot database, no mocks.
 */
@SpringBootTest(classes = CoreApplication.class)
class LoginStateCombinedRefsContractTest {

    @Autowired
    private LoginStateService loginStateService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ConfigService configService;

    private long projectId;

    @BeforeEach
    void setUp() {
        String code = "lsx" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "session-refs-" + code, code);
        projectId = jdbcTemplate.queryForObject("SELECT id FROM tp_project WHERE code = ?",
                Long.class, code);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM tp_login_state WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_credential WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
    }

    private long registerVault(String credKey) {
        jdbcTemplate.update("INSERT INTO tp_credential (project_id, name, cred_key,"
                        + " secret_cipher, status, created_at, updated_at)"
                        + " VALUES (?, ?, ?, 'ENC:unit-test', 1, now(), now())",
                projectId, credKey, credKey);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM tp_credential WHERE project_id = ? AND cred_key = ?",
                Long.class, projectId, credKey);
    }

    private LoginStateInput input(List<ExtraAuthRef> refs) {
        return new LoginStateInput("combo-state", "tester", "组合账号", "token",
                "main_key", "tok-value-1", null, List.of("https://app.test"), "备注", refs);
    }

    // ---- persistence round-trip and zero-drift semantics ----

    @Test
    void combinedRefsPersistAndReadBackThroughTheLedgerView() {
        registerVault("api_key");
        registerVault("tenant_key");
        List<ExtraAuthRef> refs = List.of(
                new ExtraAuthRef("api_key", "header", "X-API-KEY", "Bearer {{CRED:api_key}}"),
                new ExtraAuthRef("tenant_key", "header", "X-Tenant", null));
        long id = loginStateService.register(projectId, input(refs));

        LoginStateInfo view = loginStateService.get(id);
        assertEquals(2, view.extraAuthRefs().size());
        assertEquals("api_key", view.extraAuthRefs().get(0).credKey());
        assertEquals("X-API-KEY", view.extraAuthRefs().get(0).headerName());
        assertEquals("Bearer {{CRED:api_key}}", view.extraAuthRefs().get(0).valueTemplate());
        assertEquals("X-Tenant", view.extraAuthRefs().get(1).headerName());
        String raw = jdbcTemplate.queryForObject(
                "SELECT extra_auth_refs::text FROM tp_login_state WHERE id = ?", String.class, id);
        assertTrue(raw.contains("X-API-KEY"), "jsonb column must carry the reference list");
        assertTrue(!raw.contains("tok-value-1") && !raw.contains("ENC:"),
                "ledger row stores references only, never values or ciphers");
    }

    @Test
    void legacyNineArgRegisterStoresTheEmptyListDefault() {
        LoginStateInput legacy = new LoginStateInput("legacy-state", null, null, "token",
                "main_key", "tok-value-2", null, List.of(), null);
        long id = loginStateService.register(projectId, legacy);
        assertEquals(List.of(), loginStateService.get(id).extraAuthRefs());
        assertEquals("[]", jdbcTemplate.queryForObject(
                "SELECT extra_auth_refs::text FROM tp_login_state WHERE id = ?", String.class, id));
    }

    @Test
    void recaptureWithoutARefListKeepsStoredCombinedHeaders() {
        registerVault("api_key");
        loginStateService.register(projectId, input(List.of(
                new ExtraAuthRef("api_key", "header", "X-API-KEY", null))));
        LoginStateInfo renewed = loginStateService.get(loginStateService.register(projectId,
                new LoginStateInput("combo-state", null, null, "token",
                        "main_key", "tok-value-3", null, List.of(), null)));
        assertEquals(1, renewed.extraAuthRefs().size(),
                "capture-channel re-register must not wipe user-registered headers");
    }

    @Test
    void explicitEmptyListFromTheLedgerDoorClearsCombinedHeaders() {
        registerVault("api_key");
        loginStateService.register(projectId, input(List.of(
                new ExtraAuthRef("api_key", "header", "X-API-KEY", null))));
        long id = loginStateService.register(projectId, input(List.of()));
        assertEquals(List.of(), loginStateService.get(id).extraAuthRefs());
    }

    // ---- fail-closed validation branches ----

    private String expectRefused(List<ExtraAuthRef> refs) {
        ServiceException thrown = assertThrows(ServiceException.class,
                () -> loginStateService.register(projectId, input(refs)));
        return thrown.getMessage();
    }

    @Test
    void refToAnUnregisteredCredentialIsRefusedAndNamedWithItsRemedyDoor() {
        String message = expectRefused(List.of(
                new ExtraAuthRef("ghost_key", "header", "X-Ghost", null)));
        assertTrue(message.contains("ghost_key"), message);
        assertTrue(message.contains("凭据保险箱"), message);
    }

    @Test
    void headerClashingWithTheKindPrimaryHeaderIsRefused() {
        registerVault("api_key");
        String message = expectRefused(List.of(
                new ExtraAuthRef("api_key", "header", "authorization", null)));
        assertTrue(message.contains("authorization"), message);
        assertTrue(message.contains("主请求头"), message);
        assertTrue(message.contains("改用其它头名"), message);
    }

    @Test
    void duplicatedHeaderNamesInsideTheListAreRefused() {
        registerVault("k1");
        registerVault("k2");
        String message = expectRefused(List.of(
                new ExtraAuthRef("k1", "header", "X-Api-Key", null),
                new ExtraAuthRef("k2", "header", "x-api-key", null)));
        assertTrue(message.contains("出现多次"), message);
    }

    @Test
    void nonHeaderDestinationIsRefusedWithTheLegalValueNamed() {
        registerVault("api_key");
        String message = expectRefused(List.of(
                new ExtraAuthRef("api_key", "query", "X-API-KEY", null)));
        assertTrue(message.contains("header"), message);
    }

    @Test
    void valueTemplateWithoutACredentialReferenceIsRefused() {
        registerVault("api_key");
        String message = expectRefused(List.of(
                new ExtraAuthRef("api_key", "header", "X-API-KEY", "Bearer sk-plain-secret")));
        assertTrue(message.contains("{{CRED:"), message);
    }

    @Test
    void listCapIsEnforcedAndFollowsTheHotConfigKey() {
        List<ExtraAuthRef> nine = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            String key = "cap_key_" + i;
            registerVault(key);
            nine.add(new ExtraAuthRef(key, "header", "X-Cap-" + i, null));
        }
        String message = expectRefused(nine);
        assertTrue(message.contains("8"), message);
        assertTrue(message.contains("session.state.extra-auth-max-refs"), message);

        Long cfgId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_sys_config WHERE config_key = ?", Long.class,
                LoginStateServiceImpl.EXTRA_REFS_CAP_KEY);
        ConfigInput base = new ConfigInput(LoginStateServiceImpl.EXTRA_REFS_CAP_KEY, "12",
                "系统", "int", 0,
                "单个登录态可携带的附加会话头条数上限（默认 8，保存即生效；超出会被拒绝并提示当前上限，可在本页调高）");
        configService.update(1L, cfgId, base);
        try {
            long id = loginStateService.register(projectId, input(nine));
            assertEquals(9, loginStateService.get(id).extraAuthRefs().size());
        } finally {
            configService.update(1L, cfgId, new ConfigInput(base.configKey(), "8",
                    base.groupName(), base.valueType(), 0, base.description()));
        }
        assertEquals("8", configService.getValue(
                LoginStateServiceImpl.EXTRA_REFS_CAP_KEY, "0"));
    }
}
