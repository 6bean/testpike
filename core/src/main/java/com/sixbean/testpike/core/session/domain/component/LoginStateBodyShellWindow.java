package com.sixbean.testpike.core.session.domain.component;

import com.sixbean.testpike.core.events.domain.component.ProbeExchangeRunner;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Leading byte window of an exchange response, decoded as text for
 * login-shell marker scanning. Single source shared by the scheduled
 * keep-alive runner and the enable-door pre-check so both read the body
 * the same way (one window shape, one cap, one charset).
 */
public final class LoginStateBodyShellWindow {

    /** Leading window of the response text inspected for login-shell markers. */
    private static final int SHELL_SCAN_BYTES = 65536;

    private LoginStateBodyShellWindow() {
    }

    public static String text(ProbeExchangeRunner.ExchangeResult result) {
        byte[] raw = result.bodyBytes();
        if (raw == null || raw.length == 0) {
            return "";
        }
        return new String(Arrays.copyOf(raw, Math.min(raw.length, SHELL_SCAN_BYTES)),
                StandardCharsets.UTF_8);
    }
}
