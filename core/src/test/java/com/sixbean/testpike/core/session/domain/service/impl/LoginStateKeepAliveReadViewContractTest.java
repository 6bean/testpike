package com.sixbean.testpike.core.session.domain.service.impl;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.session.SessionJwtFixture;
import com.sixbean.testpike.core.session.api.dto.LoginStateInfo;
import com.sixbean.testpike.core.session.api.dto.LoginStateInput;
import com.sixbean.testpike.core.session.domain.service.LoginStateService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Read-side contract for the keep-alive display columns: every ledger read
 * (single get, project list) must carry switch state, probe target, per-row
 * cadence override and the scheduler's last outcome to the admin bridge,
 * because the row-level switch, stop-reason column and back-off note on the
 * ledger page have no other data source. Real database, no mocks.
 */
@SpringBootTest(classes = CoreApplication.class)
class LoginStateKeepAliveReadViewContractTest {

    @Autowired
    private LoginStateService loginStateService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;

    @BeforeEach
    void setUp() {
        String code = "kak" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "keepalive-read-" + code, code);
        projectId = jdbcTemplate.queryForObject("SELECT id FROM tp_project WHERE code = ?",
                Long.class, code);
        cleanRows();
    }

    @AfterEach
    void tearDown() {
        cleanRows();
        jdbcTemplate.update("DELETE FROM tp_credential WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
    }

    private void cleanRows() {
        if (projectId != 0) {
            jdbcTemplate.update("DELETE FROM tp_login_state WHERE project_id = ?", projectId);
        }
    }

    private long register(String stateName) {
        long exp = Instant.now().getEpochSecond() + 3600;
        // Credential reference keys must stay in the legal shape: letters,
        // digits and underscores only (hyphens are refused by the vault).
        String credKey = "sess_" + stateName.replace('-', '_');
        LoginStateInput input = new LoginStateInput(stateName, "approver", stateName,
                "token", credKey, SessionJwtFixture.signedWithExp(exp),
                null, List.of("https://app.test"), null);
        return loginStateService.register(projectId, input);
    }

    @Test
    void enrolledRowCarriesAllKeepAliveDisplayColumns() {
        long id = register("enrolled-row");
        Timestamp ranAt = Timestamp.from(Instant.now());
        jdbcTemplate.update("UPDATE tp_login_state SET keepalive_enabled = 1, "
                        + "keepalive_url = 'https://target.example/protected', "
                        + "keepalive_interval_minutes = 30, last_keepalive_at = ?, "
                        + "last_keepalive_verdict = 'UNKNOWN', keepalive_fail_streak = 4, "
                        + "keepalive_stop_reason = 'stopped after repeated unreachable probes' "
                        + "WHERE id = ?",
                ranAt, id);

        LoginStateInfo found = loginStateService.get(id);
        assertEquals(1, found.keepaliveEnabled());
        assertEquals("https://target.example/protected", found.keepaliveUrl());
        assertEquals(30, found.keepaliveIntervalMinutes());
        assertNotNull(found.lastKeepaliveAt());
        assertEquals("UNKNOWN", found.lastKeepaliveVerdict());
        assertEquals(4, found.keepaliveFailStreak());
        assertEquals("stopped after repeated unreachable probes", found.keepaliveStopReason());

        List<LoginStateInfo> listed = loginStateService.list(projectId, null);
        assertEquals(1, listed.size());
        assertEquals("UNKNOWN", listed.get(0).lastKeepaliveVerdict());
        assertEquals(4, listed.get(0).keepaliveFailStreak());
    }

    @Test
    void neverEnrolledRowReadsAsSwitchOffAndGlobalCadence() {
        long id = register("fresh-row");
        LoginStateInfo found = loginStateService.get(id);
        assertEquals(0, found.keepaliveEnabled());
        assertNull(found.keepaliveUrl());
        assertNull(found.keepaliveIntervalMinutes());
        assertNull(found.lastKeepaliveAt());
        assertNull(found.lastKeepaliveVerdict());
        assertEquals(0, found.keepaliveFailStreak());
        assertNull(found.keepaliveStopReason());
    }
}
