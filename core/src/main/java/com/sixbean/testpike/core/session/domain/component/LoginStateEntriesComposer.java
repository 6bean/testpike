package com.sixbean.testpike.core.session.domain.component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.session.api.dto.CaptureCommittedEntry;
import com.sixbean.testpike.core.session.api.dto.LoginStateInput;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Composes the checked subset of captured candidate rows into one ledger
 * registration input (the shared shape behind every login-state write path:
 * the CDP capture wizard's commit round and the screenshot draft landing,
 * piece D — red line: one composition, never a second copy).
 *
 * <p>One login state equals one account (D9), so the checked subset becomes
 * exactly one ledger row keyed by the state name; re-capturing the same name
 * renews that row (uk project+state_name and vault uk project+cred_key both
 * ride the name). A single entry that is exactly the token-key anchor
 * registers as a consumable bearer token; anything else is a snapshot
 * (cookie-only vs mixed origins decide cookie_set vs storage_state).
 */
@Component
public class LoginStateEntriesComposer {

    public static final String LEDGER_KIND_TOKEN = "token";
    public static final String LEDGER_KIND_COOKIE_SET = "cookie_set";
    public static final String LEDGER_KIND_STORAGE_STATE = "storage_state";

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * @param stateName   human-readable ledger name (drives the vault key digest)
     * @param anchorKind  capture-session anchor kind, blank/other = snapshot only
     * @param anchorValue token-key anchor name, matched only for a single entry
     * @param entries     the checked subset, already authorised by the human door
     * @param scopeOrigins origin whitelist carried from the capture session
     * @param lineageRemark plain-language lineage written onto the ledger row
     */
    public LoginStateInput compose(String stateName, String anchorKind, String anchorValue,
                                   List<CaptureCommittedEntry> entries,
                                   List<String> scopeOrigins, String lineageRemark) {
        String kind;
        String secret;
        CaptureCommittedEntry only = entries.size() == 1 ? entries.get(0) : null;
        if (only != null && "token-key".equals(anchorKind)
                && only.name().equals(anchorValue)) {
            kind = LEDGER_KIND_TOKEN;
            secret = only.value();
        } else {
            boolean allCookies = true;
            for (CaptureCommittedEntry entry : entries) {
                if (!"cookie".equals(entry.kind())) {
                    allCookies = false;
                    break;
                }
            }
            kind = allCookies ? LEDGER_KIND_COOKIE_SET : LEDGER_KIND_STORAGE_STATE;
            secret = toEntriesJson(entries);
        }
        return new LoginStateInput(stateName, null, null, kind, vaultKey(stateName),
                secret, null, scopeOrigins, lineageRemark);
    }

    private String toEntriesJson(List<CaptureCommittedEntry> entries) {
        List<Map<String, Object>> rows = new ArrayList<>(entries.size());
        for (CaptureCommittedEntry entry : entries) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("kind", entry.kind());
            row.put("name", entry.name());
            row.put("value", entry.value());
            row.put("origin", entry.origin());
            row.put("expiresAtEpochSecond", entry.expiresAtEpochSecond());
            rows.add(row);
        }
        try {
            return objectMapper.writeValueAsString(rows);
        } catch (JsonProcessingException e) {
            throw new ServiceException("勾选条目无法入册——请到「登录态账本」对该行重新发起采集并勾选确认后再入册。", BizErrorCode.INTERNAL_ERROR);
        }
    }

    /**
     * The vault key scheme is ASCII ([A-Za-z_][A-Za-z0-9_]*, max 64) while a
     * state name is a human-readable Chinese label, so the capture lineage
     * derives a stable key from the name digest — the same name always renews
     * the same vault row (D9 update-not-append), different names never collide.
     */
    private static String vaultKey(String stateName) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(stateName.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder("capture_");
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable for the capture vault key", e);
        }
    }
}
