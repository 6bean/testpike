package com.sixbean.testpike.core.mcp.domain.component;

import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;

/**
 * Single-source wording for MCP network failures shown in the health column.
 *
 * <p>Both the probe leg and the tool-call leg unwrap the IOException chain the
 * same way, so the classification lives here once. Some JDK IOException
 * implementations carry a null message (e.g. a bare SocketTimeoutException or
 * an EOF raised without detail); appending {@code getMessage()} verbatim leaked
 * the literal "null" into the user-facing health message, so this helper always
 * falls back to the exception's simple class name.
 */
public final class McpNetworkFailureText {

    private McpNetworkFailureText() {
        // static-only helper; no instance
    }

    /**
     * Human-readable reason for a connectivity failure, never containing "null".
     *
     * @param e the IOException raised by the HTTP client (cause chain may be deeper)
     * @return Chinese wording shown as the health message
     */
    public static String describe(IOException e) {
        Throwable rootCause = e;
        while (rootCause.getCause() != null && rootCause.getCause() != rootCause) {
            rootCause = rootCause.getCause();
        }
        if (rootCause instanceof ConnectException || rootCause instanceof UnknownHostException) {
            return "网络不可达：" + rootCause.getClass().getSimpleName();
        }
        String detail = rootCause.getMessage();
        return detail != null && !detail.isBlank()
                ? detail
                : "网络异常：" + rootCause.getClass().getSimpleName();
    }
}
