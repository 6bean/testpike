package com.sixbean.testpike.core.events.domain.component;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.config.domain.component.ConfigCipher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hit matrix of the single-source secret vocabulary and the sealed/masked
 * shapes the exchange trail may carry (full-exchange debug data with the
 * plaintext-never-in-the-event-stream red line held).
 */
class ExchangeSecretLedgerTest {

    private static final String HEX = "00112233445566778899aabbccddeeff";

    private static ExchangeSecretLedger ledger(boolean cipherEnabled) {
        return new ExchangeSecretLedger(new ConfigCipher(cipherEnabled ? HEX : ""));
    }

    @Test
    void headerFamilyHitsCaseInsensitively() {
        for (String name : new String[]{"Authorization", "authorization", "COOKIE",
                "Set-Cookie", "Proxy-Authorization", "WWW-Authenticate", "www-authenticate"}) {
            assertTrue(ExchangeSecretLedger.isSensitiveHeader(name), name + " must hit");
        }
        for (String name : new String[]{"Content-Type", "Accept", "User-Agent",
                "X-Trace-Id", "Location", null, ""}) {
            assertFalse(ExchangeSecretLedger.isSensitiveHeader(name), name + " must not hit");
        }
    }

    @Test
    void bodyFieldFamilyHitsOnWordStems() {
        for (String field : new String[]{"password", "Password", "refresh", "refresh_token",
                "access_key", "accessKey", "clientSecret", "secret", "id_token", "TOKEN"}) {
            assertTrue(ExchangeSecretLedger.isSensitiveBodyField(field), field + " must hit");
        }
        for (String field : new String[]{"username", "email", "rememberMe", "redirectUrl"}) {
            assertFalse(ExchangeSecretLedger.isSensitiveBodyField(field), field + " must not hit");
        }
    }

    @Test
    void maskShowsEightCharPrefixAndLengthOnly() {
        String secret = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.sealed-payload";
        String masked = ledger(true).mask(secret);
        assertTrue(masked.startsWith("eyJhbGci"), "prefix fingerprint expected: " + masked);
        assertTrue(masked.contains("总长度 " + secret.length()), "length fingerprint expected: " + masked);
        assertFalse(masked.contains(secret.substring(8)), "anything beyond the 8-char prefix must not leak");
    }

    @Test
    void shortValuesKeepNoReadablePrefix() {
        String masked = ledger(true).mask("abc");
        assertFalse(masked.contains("abc"), "a value at or below the prefix budget must stay fully masked");
        assertTrue(masked.contains("总长度 3"), "length fingerprint still shown: " + masked);
    }

    @Test
    void sealProducesCiphertextThatRoundTripsThroughUnseal() {
        ExchangeSecretLedger ledger = ledger(true);
        String secret = "SESSIONID=super-secret-value-123";
        String sealed = ledger.seal(secret);
        assertTrue(sealed.startsWith(ExchangeSecretLedger.SEAL_PREFIX), sealed);
        assertFalse(sealed.contains("super-secret-value-123"), "sealed form must not carry plaintext");
        assertTrue(ledger.isSealed(sealed));
        assertFalse(ledger.isSealed(secret));
        assertEquals(secret, ledger.unseal(sealed));
    }

    @Test
    void sealWithoutKeyFallsBackHonestlyWithoutPlaintext() {
        ExchangeSecretLedger ledger = ledger(false);
        String sealed = ledger.seal("topsecret-material");
        assertFalse(sealed.contains("topsecret-material"), "disabled cipher must never pass plaintext through");
        assertFalse(ledger.isSealed(sealed), "fallback marker is not ciphertext");
        assertTrue(sealed.contains("加密"), "the fallback must name why nothing was stored: " + sealed);
    }

    @Test
    void unsealRejectsAnythingNotSealed() {
        assertThrows(ServiceException.class, () -> ledger(true).unseal("plain-cookie-value"));
    }
}
