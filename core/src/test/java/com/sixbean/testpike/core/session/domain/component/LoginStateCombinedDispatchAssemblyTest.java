package com.sixbean.testpike.core.session.domain.component;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.credential.api.dto.CredentialInput;
import com.sixbean.testpike.core.credential.domain.service.CredentialService;
import com.sixbean.testpike.core.events.domain.component.ProbeExchangeRunner;
import com.sixbean.testpike.core.session.api.dto.ExtraAuthRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * (dispatch assembly): the combined-session header rows a
 * login state rides alongside its kind primary header are materialised at
 * the dispatch instant from the vault only — primary names always win,
 * duplicate rows keep the first, non-header destinations never materialise,
 * a vanished credential fails with the vault remedy named, and the trail
 * render masks every dispatched ref row regardless of the word list (the
 * plaintext-never-in-the-event-stream red line). Real slot database, no
 * mocks.
 */
@SpringBootTest(classes = CoreApplication.class)
class LoginStateCombinedDispatchAssemblyTest {

    @Autowired
    private SessionStateSupport sessionStateSupport;

    @Autowired
    private CredentialService credentialService;

    @Autowired
    private ProbeExchangeRunner runner;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;

    @BeforeEach
    void setUp() {
        String code = "ldx" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "dispatch-assembly-" + code, code);
        projectId = jdbcTemplate.queryForObject("SELECT id FROM tp_project WHERE code = ?",
                Long.class, code);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM tp_credential WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
    }

    private long credential(String credKey, String secret) {
        return credentialService.create(projectId,
                new CredentialInput(credKey, credKey, secret, "dispatch assembly fixture"));
    }

    private ExtraAuthRef ref(String credKey, String headerName, String template) {
        return new ExtraAuthRef(credKey, ExtraAuthRef.INTO_HEADER, headerName, template);
    }

    @Test
    void dispatchMaterialisesVaultPlaintextPerRefIncludingTemplateFill() {
        credential("api_key", "sk-live-1234567890");
        credential("tok", "tok-abcdefghijklmnop");
        Map<String, String> headers = sessionStateSupport.extraAuthHeaders(projectId, "cookie_set", List.of(
                ref("api_key", "X-API-KEY", null),
                ref("tok", "Authorization", "Bearer {{CRED:tok}}")));
        assertEquals("sk-live-1234567890", headers.get("X-API-KEY"));
        assertEquals("Bearer tok-abcdefghijklmnop", headers.get("Authorization"));
    }

    @Test
    void primaryHeaderOfKindAlwaysWinsOverAClashingRef() {
        credential("tok2", "second-row-value");
        // Save-time rejects this shape; the dispatch door stays defensive so
        // even a hand-edited legacy row can never overwrite the real session.
        Map<String, String> headers = sessionStateSupport.extraAuthHeaders(projectId, "token", List.of(
                ref("tok2", "authorization", null),
                ref("tok2", "X-Tenant-Key", null)));
        assertFalse(headers.containsKey("authorization"), "primary must not be displaced");
        assertEquals("second-row-value", headers.get("X-Tenant-Key"));
    }

    @Test
    void duplicateHeaderNameInListKeepsTheFirstRow() {
        credential("first_key", "value-one");
        credential("second_key", "value-two");
        Map<String, String> headers = sessionStateSupport.extraAuthHeaders(projectId, "cookie_set", List.of(
                ref("first_key", "X-Site-Token", null),
                ref("second_key", "x-site-token", null)));
        assertEquals(1, headers.size());
        assertEquals("value-one", headers.get("X-Site-Token"));
    }

    @Test
    void nonHeaderDestinationNeverMaterialises() {
        credential("q_key", "query-value");
        Map<String, String> headers = sessionStateSupport.extraAuthHeaders(projectId, "cookie_set",
                List.of(new ExtraAuthRef("q_key", "query", "X-Should-Not-Exist", null)));
        assertTrue(headers.isEmpty());
    }

    @Test
    void emptyAndNullRefListsAreNoOpsForLegacyRows() {
        assertTrue(sessionStateSupport.extraAuthHeaders(projectId, "token", List.of()).isEmpty());
        assertTrue(sessionStateSupport.extraAuthHeaders(projectId, "token", null).isEmpty());
    }

    @Test
    void vanishedCredentialFailsLoudlyWithTheVaultRemedyNamed() {
        ServiceException refused = assertThrows(ServiceException.class, () ->
                sessionStateSupport.extraAuthHeaders(projectId, "cookie_set",
                        List.of(ref("ghost_key", "X-Ghost", null))));
        assertTrue(refused.getMessage().contains("ghost_key"), "names the missing ref: " + refused.getMessage());
        assertTrue(refused.getMessage().contains("凭据保险箱"), "points at the vault door: " + refused.getMessage());
    }

    @Test
    void templatePlaceholderOfAnotherCredentialResolvesIndependently() {
        credential("sign_secret", "SIGN-9001");
        credential("tok", "tok-abcdefghijklmnop");
        Map<String, String> headers = sessionStateSupport.extraAuthHeaders(projectId, "cookie_set",
                List.of(new ExtraAuthRef("sign_secret", "header", "X-Combo",
                        "{{CRED:sign_secret}}+{{CRED:tok}}")));
        assertEquals("SIGN-9001+tok-abcdefghijklmnop", headers.get("X-Combo"));
    }

    @Test
    void trailRenderMasksDispatchedRefRowsBeyondTheWordList() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Cookie", "sid=abc123def456");
        headers.put("X-Session-Combo", "vault-material-777");
        headers.put("X-Trace", "plain-observability-value");
        ProbeExchangeRunner.TrailPiece masked = runner.renderRequestTrail(
                "GET", "https://app.test/api", headers, Set.of("x-session-combo"));
        assertFalse(masked.text().contains("vault-material-777"),
                "dispatched ref plaintext must never reach the event stream body");
        assertTrue(masked.text().contains("x-session-combo: "), "the row itself stays visible (masked, not dropped)");
        assertTrue(masked.text().contains("plain-observability-value"), "non-secret rows render unchanged");
        assertTrue(masked.sealed() != null && masked.sealed().contains("request:x-session-combo"),
                "masked ref row is sealed for the gated reveal door");

        ProbeExchangeRunner.TrailPiece legacy = runner.renderRequestTrail(
                "GET", "https://app.test/api", headers);
        assertTrue(legacy.text().contains("vault-material-777"),
                "three-arg door keeps its exact old form (zero drift for other consumers)");
    }
}
