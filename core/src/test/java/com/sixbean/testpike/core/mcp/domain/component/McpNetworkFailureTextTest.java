package com.sixbean.testpike.core.mcp.domain.component;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the single-source wording for MCP network failures shown in the
 * health column ( vision remediation: a null IOException message used to
 * leak the literal "null" into the user-facing health message).
 */
class McpNetworkFailureTextTest {

    @Test
    void connectExceptionIsWordedAsUnreachableWithClassName() {
        String text = McpNetworkFailureText.describe(new ConnectException());
        assertEquals("网络不可达：ConnectException", text);
    }

    @Test
    void unknownHostIsWordedAsUnreachableWithClassName() {
        String text = McpNetworkFailureText.describe(new UnknownHostException());
        assertEquals("网络不可达：UnknownHostException", text);
    }

    @Test
    void wrappedConnectExceptionIsUnwrappedToTheSameWording() {
        IOException wrapper = new IOException("request failed", new ConnectException());
        assertEquals("网络不可达：ConnectException", McpNetworkFailureText.describe(wrapper));
    }

    @Test
    void nullMessageFallbackCarriesClassNameAndNeverLiteralNull() {
        // SocketTimeoutException raised by some JDK paths has a null message;
        String text = McpNetworkFailureText.describe(new SocketTimeoutException());
        assertFalse(text.contains("null"), () -> "wording leaked a literal null: " + text);
        assertTrue(text.contains("SocketTimeoutException"), text);
    }

    @Test
    void nonNullMessageIsKeptVerbatimForOtherIoFailures() {
        String text = McpNetworkFailureText.describe(new IOException("connection reset by peer"));
        assertEquals("connection reset by peer", text);
    }

    @Test
    void blankMessageAlsoFallsBackToClassName() {
        String text = McpNetworkFailureText.describe(new IOException("   "));
        assertTrue(text.startsWith("网络异常："), text);
        assertFalse(text.contains("null"), text);
    }
}
