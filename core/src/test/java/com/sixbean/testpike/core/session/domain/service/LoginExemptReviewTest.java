package com.sixbean.testpike.core.session.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.credential.api.dto.CredentialInput;
import com.sixbean.testpike.core.credential.domain.service.CredentialService;
import com.sixbean.testpike.core.session.api.dto.LoginExemptInfo;
import com.sixbean.testpike.core.session.api.dto.LoginExemptInput;
import com.sixbean.testpike.core.session.api.dto.LoginExemptReviewSignal;
import com.sixbean.testpike.core.session.domain.component.LoginExemptSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Review write-back round-trip on the real slot6 database (AV5 re-check face):
 * the verdict lands on the same jsonb slot as the declaration it re-checks, so
 * the ledger page and the pre-flight gate read one source (red line 6).
 *
 * <p>Contract pinned: an incoherent history is preserved as the latest verdict
 * only (re-review overwrites in place, never appends), a re-declaration resets
 * the slot to pending so an old pass cannot vouch for a new claim, the
 * reviewedAt stamp is written by the server, and reviewing an environment with
 * no declaration is refused honestly.
 */
@SpringBootTest(classes = CoreApplication.class)
class LoginExemptReviewTest {

    @Autowired
    private LoginExemptService loginExemptService;

    @Autowired
    private CredentialService credentialService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;
    private long envId;

    @BeforeEach
    void setUp() {
        String code = "lmrv" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "login-exempt-review-" + code, code);
        projectId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_project WHERE code = ?", Long.class, code);
        jdbcTemplate.update(
                "INSERT INTO tp_project_env (project_id, name, env_type, base_url) "
                        + "VALUES (?, '复核环境', 1, 'http://fixture.local')", projectId);
        envId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_project_env WHERE project_id = ? AND name = ?",
                Long.class, projectId, "复核环境");
    }

    @AfterEach
    void cleanUp() {
        // teardown order mirrors the FK graph: credential/env rows hang off
        // the project row, so they go first (fixture-built data, cleaned by
        // id anchor — never TRUNCATE factory tables).
        jdbcTemplate.update("DELETE FROM tp_credential WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_project_env WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
    }

    private void declareMasterCode() {
        credentialService.create(projectId, new CredentialInput(
                "复核万能码夹具", "lmrv_master_" + projectId, "FIXED-REVIEW-1", "fixture"));
        loginExemptService.declare(envId, new LoginExemptInput(
                LoginExemptSupport.KIND_MASTER_CODE, "lmrv_master_" + projectId, List.of(),
                LoginExemptSupport.REVIEW_TRIAL_LOGIN));
    }

    @Test
    void failedReviewLandsOnTheSlotWithServerStampedTime() {
        declareMasterCode();
        LoginExemptInfo after = loginExemptService.recordReview(envId, new LoginExemptReviewSignal(
                "master_code_rejected", "被测环境反馈万能码已失效"));
        assertEquals(LoginExemptSupport.STATUS_FAILED, after.reviewStatus());
        assertTrue(after.reviewDetail().contains("万能码"), after.reviewDetail());
        assertTrue(after.reviewedAt() != null && !after.reviewedAt().isBlank(),
                "reviewedAt is a server fact, not caller-supplied");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT login_exempt_json::text AS j FROM tp_project_env WHERE id = ?", envId);
        assertTrue(((String) rows.get(0).get("j")).contains("master_code_rejected")
                        || ((String) rows.get(0).get("j")).contains("未通过复核"),
                "the verdict must be stored, not only echoed");
    }

    @Test
    void reReviewOverwritesInPlaceAndKeepsTheDeclaration() {
        declareMasterCode();
        loginExemptService.recordReview(envId, new LoginExemptReviewSignal("undetermined", null));
        LoginExemptInfo second = loginExemptService.recordReview(envId,
                new LoginExemptReviewSignal("trial_login_ok", null));
        assertEquals(LoginExemptSupport.STATUS_PASSED, second.reviewStatus());
        assertEquals(LoginExemptSupport.KIND_MASTER_CODE, second.kind(),
                "a re-check must not rewrite the declaration itself");
        assertTrue(second.credKey() != null && !second.credKey().isBlank(), "vault key reference stays");
    }

    @Test
    void declaringAgainResetsAStalePassToPending() {
        declareMasterCode();
        loginExemptService.recordReview(envId, new LoginExemptReviewSignal("trial_login_ok", null));
        LoginExemptInfo redeclared = loginExemptService.declare(envId, new LoginExemptInput(
                LoginExemptSupport.KIND_WHITELIST_ACCOUNTS, null,
                List.of("auditor@example.com"), LoginExemptSupport.REVIEW_TRIAL_LOGIN));
        assertEquals(LoginExemptSupport.STATUS_PENDING, redeclared.reviewStatus(),
                "a new claim is never vouched for by the previous review");
    }

    @Test
    void reviewingAnUndeclaredEnvironmentIsRefusedHonestly() {
        ServiceException refused = assertThrows(ServiceException.class,
                () -> loginExemptService.recordReview(envId,
                        new LoginExemptReviewSignal("trial_login_ok", null)));
        assertTrue(refused.getMessage().contains("尚未登记"), refused.getMessage());
    }

    @Test
    void missingEnvironmentIsRefusedWithTheObjectNamed() {
        ServiceException refused = assertThrows(ServiceException.class,
                () -> loginExemptService.recordReview(999_999_999L,
                        new LoginExemptReviewSignal("trial_login_ok", null)));
        assertTrue(refused.getMessage().contains("不存在"), refused.getMessage());
    }

    @Test
    void noSecretValueEverEntersTheReviewSlot() {
        declareMasterCode();
        loginExemptService.recordReview(envId, new LoginExemptReviewSignal(
                "master_code_rejected", "口令值不该出现在这里"));
        String stored = jdbcTemplate.queryForObject(
                "SELECT login_exempt_json::text FROM tp_project_env WHERE id = ?", String.class, envId);
        assertTrue(!stored.contains("ENC:"), "the vault ciphertext has no path into this slot");
        assertTrue(!stored.toLowerCase().contains("password"), stored);
    }
}
