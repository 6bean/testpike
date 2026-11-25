package com.sixbean.testpike.admin.aichat.app.component;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;

import java.io.IOException;
import java.io.PrintWriter;

/**
 * Test response that enforces the servlet spec's byte-stream/Writer
 * exclusivity the way Tomcat does: once getOutputStream() has been handed
 * out - which is exactly what the SSE handler does for every real frame -
 * getWriter() must fail with IllegalStateException. A bare
 * MockHttpServletResponse allows both accessors side by side, which is how
 * the raw-notice fallback could look green in-process yet deliver zero
 * frames on the real container (live re-run 2026-11-26).
 */
final class StreamExclusiveHttpServletResponse extends HttpServletResponseWrapper {

    private boolean streamOwned;

    StreamExclusiveHttpServletResponse(HttpServletResponse delegate) {
        super(delegate);
    }

    @Override
    public ServletOutputStream getOutputStream() throws IOException {
        streamOwned = true;
        return super.getOutputStream();
    }

    @Override
    public PrintWriter getWriter() throws IOException {
        if (streamOwned) {
            throw new IllegalStateException(
                    "getWriter() cannot be called after getOutputStream() "
                            + "has been called for this response");
        }
        return super.getWriter();
    }
}
