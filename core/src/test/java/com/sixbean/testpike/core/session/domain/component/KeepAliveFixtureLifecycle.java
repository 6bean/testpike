package com.sixbean.testpike.core.session.domain.component;

import com.sun.net.httpserver.HttpServer;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Shared lifecycle helpers for the local fixture servers used by the
 * keep-alive L2 suites (start + base-url capture, null-safe stop, and the
 * project-scoped row cleanup), so the two suites consume one boilerplate
 * source instead of copying it.
 */
final class KeepAliveFixtureLifecycle {

    private KeepAliveFixtureLifecycle() {
    }

    /** Stops the fixture server and deletes every row the suite seeded under the project. */
    static void teardownFixture(HttpServer server, JdbcTemplate jdbc, long project) {
        stopQuietly(server);
        jdbc.update("DELETE FROM tp_login_state WHERE project_id = ?", project);
        jdbc.update("DELETE FROM tp_credential WHERE project_id = ?", project);
        jdbc.update("DELETE FROM tp_project WHERE id = ?", project);
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
