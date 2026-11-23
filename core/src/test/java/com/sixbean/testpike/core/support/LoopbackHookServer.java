package com.sixbean.testpike.core.support;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;

/**
 * Single-source launcher for the loopback webhook stub shared by the
 * notification-channel suites (R40 — the interactive-card and webhook-channel
 * suites bind an ephemeral 127.0.0.1 server with one /hook context; only the
 * handler behaviour differs per suite). The port is always 0-assigned, so two
 * suites never race on a fixed port and no real external endpoint is contacted.
 */
public final class LoopbackHookServer {

    private LoopbackHookServer() {
    }

    /** Start a loopback server exposing the handler under /hook on a free port. */
    public static HttpServer startHandler(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", handler);
        server.start();
        return server;
    }

    /** Bound port of a started stub (call after startHandler). */
    public static int portOf(HttpServer server) {
        return server.getAddress().getPort();
    }

    /** Stop a stub if one was started (AfterEach-safe: tolerates a null field). */
    public static void stopQuietly(HttpServer server) {
        if (server != null) {
            server.stop(0);
        }
    }
}
