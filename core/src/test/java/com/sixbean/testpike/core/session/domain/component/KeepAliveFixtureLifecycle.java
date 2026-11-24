package com.sixbean.testpike.core.session.domain.component;

import com.sun.net.httpserver.HttpServer;

/**
 * Shared lifecycle helpers for the local fixture servers used by the
 * keep-alive L2 suites (start + base-url capture, null-safe stop), so the
 * two suites consume one boilerplate source instead of copying it.
 */
final class KeepAliveFixtureLifecycle {

    private KeepAliveFixtureLifecycle() {
    }

    static String startAndGetBase(HttpServer server) {
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    static void stopQuietly(HttpServer server) {
        if (server != null) {
            server.stop(0);
        }
    }
}
