package com.sixbean.testpike.core.config.domain.component;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Startup-time contract of the cipher key: malformed CONFIG_AES_KEY values
 * must fail fast with an actionable message naming the key and its expected
 * hex shape, never as a bare NumberFormatException, and never echoing the
 * value itself. Blank stays a legitimate "cipher disabled" state.
 */
class ConfigCipherTest {

    private static final String HEX16 = "00112233445566778899aabbccddeeff";
    private static final String HEX24 = HEX16 + "0011223344556677";
    private static final String HEX32 = HEX16 + "00112233445566778899aabbccddeeff";

    @Test
    @DisplayName("blank key = disabled cipher, no exception")
    void blankKeyDisablesCipher() {
        assertFalse(new ConfigCipher("").isEnabled());
        assertFalse(new ConfigCipher("   ").isEnabled());
    }

    @Test
    @DisplayName("accepted key lengths enable the cipher")
    void validHexKeysEnable() {
        assertTrue(new ConfigCipher(HEX16).isEnabled());
        assertTrue(new ConfigCipher(HEX24).isEnabled());
        assertTrue(new ConfigCipher(HEX32).isEnabled());
        assertTrue(new ConfigCipher(HEX32.toUpperCase()).isEnabled());
        assertTrue(new ConfigCipher(" " + HEX16 + " ").isEnabled());
    }

    @Test
    @DisplayName("non-hex characters fail fast naming the key, never the value")
    void nonHexFailsFastWithActionableMessage() {
        String leakedStyleKey = "0VerySecretPassphraseThatIsNotHexAtAll";
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> new ConfigCipher(leakedStyleKey));
        String message = thrown.getMessage();
        assertTrue(message.contains("CONFIG_AES_KEY"), message);
        assertTrue(message.contains("openssl rand -hex 32"), message);
        assertTrue(message.contains("32, 48 or 64"), message);
        assertFalse(message.contains(leakedStyleKey), "the key value must never reach the log");
    }

    @Test
    @DisplayName("hex of a rejected length fails fast instead of silently disabling")
    void wrongLengthHexFailsFast() {
        String fortyHex = HEX32 + "abcd";
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> new ConfigCipher(fortyHex));
        assertTrue(thrown.getMessage().contains("CONFIG_AES_KEY"), thrown.getMessage());
    }

    @Test
    @DisplayName("mask constant and round-trip stay intact for a valid key")
    void validKeyStillRoundTrips() {
        ConfigCipher cipher = new ConfigCipher(HEX16);
        assertTrue(cipher.isEnabled());
        String sealed = cipher.encrypt("db-password");
        assertEquals("db-password", cipher.decrypt(sealed));
        assertEquals("******", ConfigCipher.MASK);
        assertDoesNotThrow(() -> cipher.decrypt(sealed));
    }
}
