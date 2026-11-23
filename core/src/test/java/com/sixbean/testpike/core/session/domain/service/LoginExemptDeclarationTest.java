package com.sixbean.testpike.core.session.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.credential.api.dto.CredentialInput;
import com.sixbean.testpike.core.credential.domain.service.CredentialService;
import com.sixbean.testpike.core.session.api.dto.LoginExemptInfo;
import com.sixbean.testpike.core.session.api.dto.LoginExemptInput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ledger round-trip for the login exemption declaration (AV5 registration
 * face): the declaration rides one structured jsonb slot on the environment
 * profile row, re-declaring overwrites in place, legacy rows keep a NULL slot
 * (zero drift for every existing environment read path).
 *
 * <p>Vault discipline is part of the contract pinned here: the master code is
 * referenced by a cred_key that must already exist in the SAME project vault
 * (cross-project keys are refused — login exemption is a project asset), and
 * no plaintext value has a storage path through this face at all.
 *
 * <p>Real slot6 database, zero mocks; fixture-owned rows cleaned per id.
 */
@SpringBootTest(classes = CoreApplication.class)
class LoginExemptDeclarationTest {

    @Autowired
    private LoginExemptService loginExemptService;

    @Autowired
    private CredentialService credentialService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;
    private long envId;
    private long otherProjectId;

    @BeforeEach
    void setUp() {
        String code = "lmex" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "login-exempt-" + code, code);
        projectId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_project WHERE code = ?", Long.class, code);
        jdbcTemplate.update(
                "INSERT INTO tp_project_env (project_id, name, env_type, base_url) "
                        + "VALUES (?, '测试环境', 1, 'http://fixture.local')", projectId);
        envId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_project_env WHERE project_id = ? AND name = ?",
                Long.class, projectId, "测试环境");
        String otherCode = code + "b";
        jdbcTemplate.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "login-exempt-other-" + otherCode, otherCode);
        otherProjectId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_project WHERE code = ?", Long.class, otherCode);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM tp_project_env WHERE project_id IN (?, ?)",
                projectId, otherProjectId);
        jdbcTemplate.update("DELETE FROM tp_credential WHERE project_id IN (?, ?)",
                projectId, otherProjectId);
        jdbcTemplate.update("DELETE FROM tp_project WHERE id IN (?, ?)",
                projectId, otherProjectId);
    }

    @Test
    void undeclaredEnvironmentReadsHonestUndeclaredAndKeepsNullColumn() {
        LoginExemptInfo view = loginExemptService.find(envId);
        assertFalse(view.declared());
        assertEquals(envId, view.envId());
        assertEquals(projectId, view.projectId());
        String raw = jdbcTemplate.queryForObject(
                "SELECT login_exempt_json::text FROM tp_project_env WHERE id = ?",
                String.class, envId);
        assertNull(raw);
    }

    @Test
    void masterCodeDeclarationRoundTripsWithPendingReview() {
        credentialService.create(projectId, new CredentialInput(
                "万能码夹具", "lmex_master_" + projectId, "FIXED-123456", "fixture"));
        loginExemptService.declare(envId, new LoginExemptInput(
                "master_code", "lmex_master_" + projectId, null, "trial_login"));

        LoginExemptInfo view = loginExemptService.find(envId);
        assertTrue(view.declared());
        assertEquals("master_code", view.kind());
        assertEquals("lmex_master_" + projectId, view.credKey());
        assertEquals("trial_login", view.reviewMethod());
        assertEquals("pending", view.reviewStatus());
        String raw = jdbcTemplate.queryForObject(
                "SELECT login_exempt_json::text FROM tp_project_env WHERE id = ?",
                String.class, envId);
        assertNotNull(raw);
        assertFalse(raw.contains("FIXED-123456"), "secret value must have no storage path");
    }

    @Test
    void reDeclaringOverwritesTheSlotInPlace() {
        credentialService.create(projectId, new CredentialInput(
                "万能码夹具", "lmex_over_" + projectId, "FIXED-1", "fixture"));
        loginExemptService.declare(envId, new LoginExemptInput(
                "master_code", "lmex_over_" + projectId, null, "trial_login"));
        loginExemptService.declare(envId, new LoginExemptInput(
                "whitelist_accounts", null, List.of("qa_boss"), "manual"));

        LoginExemptInfo view = loginExemptService.find(envId);
        assertEquals("whitelist_accounts", view.kind());
        assertEquals(List.of("qa_boss"), view.accounts());
        assertNull(view.credKey());
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_project_env WHERE id = ?", Integer.class, envId));
    }

    @Test
    void masterCodeWithUnknownVaultKeyIsRefusedPointingAtTheVaultPage() {
        ServiceException ex = assertThrows(ServiceException.class, () -> loginExemptService.declare(
                envId, new LoginExemptInput(
                        "master_code", "lmex_ghost_key", null, "trial_login")));
        assertTrue(ex.getMessage().contains("凭据管理"), ex.getMessage());
    }

    @Test
    void vaultKeyFromAnotherProjectIsNotAccepted() {
        credentialService.create(otherProjectId, new CredentialInput(
                "他项目凭据", "lmex_foreign_" + otherProjectId, "X", "fixture"));
        assertThrows(ServiceException.class, () -> loginExemptService.declare(
                envId, new LoginExemptInput(
                        "master_code", "lmex_foreign_" + otherProjectId, null, "trial_login")));
    }

    @Test
    void declaringOnAMissingEnvironmentIsRefusedHonestly() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> loginExemptService.declare(999_999_999L,
                        new LoginExemptInput("none", null, null, "manual")));
        assertTrue(ex.getMessage().contains("环境"), ex.getMessage());
    }
}
