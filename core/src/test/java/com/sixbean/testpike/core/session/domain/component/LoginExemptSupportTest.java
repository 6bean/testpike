package com.sixbean.testpike.core.session.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.session.api.dto.LoginExemptInfo;
import com.sixbean.testpike.core.session.api.dto.LoginExemptInput;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Structured validation for the login exemption declaration (AV5, design doc
 *
 * <p>Contract pinned here: the three legal exemption forms and their required
 * companion fields, the review-method enum, the normalization of the account
 * list, and the vault discipline — the master-code VALUE is never a field of
 * this shape (only a cred_key reference rides through, secret material stays
 * in tp_credential, red line 3). Unknown forms are refused with wording that
 * lists the legal options instead of a bare rejection.
 *
 * <p>Pure component, real slot database not involved; zero mocks.
 */
class LoginExemptSupportTest {

    private final LoginExemptSupport support = new LoginExemptSupport(new ObjectMapper());
    private final ObjectMapper json = new ObjectMapper();

    private JsonNode storage(LoginExemptInput input) throws Exception {
        return json.readTree(support.toStorageJson(input));
    }

    @Test
    void noneFormClearsCompanionFieldsAndStartsPendingReview() throws Exception {
        JsonNode node = storage(new LoginExemptInput(
                "none", "stray-key", List.of("someone"), "manual"));
        assertEquals("none", node.path("kind").asText());
        assertTrue(node.path("credKey").isMissingNode() || node.path("credKey").isNull());
        assertTrue(node.path("accounts").isMissingNode()
                || node.path("accounts").size() == 0);
        assertEquals("manual", node.path("reviewMethod").asText());
        assertEquals("pending", node.path("reviewStatus").asText());
    }

    @Test
    void masterFormRequiresCredKey() {
        ServiceException ex = assertThrows(ServiceException.class, () -> support.toStorageJson(
                new LoginExemptInput("master_code", "  ", null, "trial_login")));
        assertTrue(ex.getMessage().contains("凭据"), ex.getMessage());
    }

    @Test
    void masterFormKeepsCredKeyAndDropsAccounts() throws Exception {
        JsonNode node = storage(new LoginExemptInput(
                "master_code", "master_code_1", List.of("ignored"), "trial_login"));
        assertEquals("master_code_1", node.path("credKey").asText());
        assertEquals(0, node.path("accounts").size());
    }

    @Test
    void masterFormRejectsNonAsciiCredKey() {
        ServiceException ex = assertThrows(ServiceException.class, () -> support.toStorageJson(
                new LoginExemptInput("master_code", "万能码", null, "trial_login")));
        assertTrue(ex.getMessage().contains("格式"), ex.getMessage());
    }

    @Test
    void whitelistFormRequiresAtLeastOneAccount() {
        ServiceException ex = assertThrows(ServiceException.class, () -> support.toStorageJson(
                new LoginExemptInput("whitelist_accounts", null, List.of("  "), "manual")));
        assertTrue(ex.getMessage().contains("白名单账号"), ex.getMessage());
    }

    @Test
    void whitelistFormTrimsDedupesAndCapsTheListing() throws Exception {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < LoginExemptSupport.MAX_ACCOUNTS + 1; i++) {
            many.add("acct-" + i);
        }
        ServiceException overCap = assertThrows(ServiceException.class,
                () -> support.toStorageJson(
                        new LoginExemptInput("whitelist_accounts", null, many, "manual")));
        assertTrue(overCap.getMessage().contains("上限"), overCap.getMessage());

        JsonNode node = storage(new LoginExemptInput(
                "whitelist_accounts", null, List.of(" a ", "a", " b "), "manual"));
        List<String> normalized = new ArrayList<>();
        for (JsonNode entry : node.path("accounts")) {
            normalized.add(entry.asText());
        }
        assertEquals(List.of("a", "b"), normalized);
    }

    @Test
    void unknownFormIsRefusedListingTheLegalOptions() {
        ServiceException ex = assertThrows(ServiceException.class, () -> support.toStorageJson(
                new LoginExemptInput("captcha_solver", null, null, "manual")));
        assertTrue(ex.getMessage().contains("可选"), ex.getMessage());
    }

    @Test
    void reviewMethodIsEnumChecked() {
        assertThrows(ServiceException.class, () -> support.toStorageJson(
                new LoginExemptInput("none", null, null, null)));
        ServiceException ex = assertThrows(ServiceException.class, () -> support.toStorageJson(
                new LoginExemptInput("none", null, null, "auto_solve")));
        assertTrue(ex.getMessage().contains("复核方式"), ex.getMessage());
    }

    @Test
    void storageShapeCarriesNoSecretValueField() throws Exception {
        List<String> keys = new ArrayList<>();
        storage(new LoginExemptInput("master_code", "k1", null, "trial_login"))
                .fieldNames().forEachRemaining(keys::add);
        assertTrue(keys.containsAll(List.of("kind", "credKey", "accounts",
                "reviewMethod", "reviewStatus")));
        assertFalse(keys.contains("value"), keys.toString());
        assertFalse(keys.contains("secret"), keys.toString());
    }

    @Test
    void missingStorageReadsHonestUndeclared() {
        LoginExemptInfo view = support.viewOf(7L, 1L, null);
        assertFalse(view.declared());
        assertNull(view.kind());
    }

    @Test
    void brokenStorageFallsBackToUndeclaredInsteadOfCrashing() {
        LoginExemptInfo view = support.viewOf(7L, 1L, "{not json");
        assertFalse(view.declared());
    }
}
