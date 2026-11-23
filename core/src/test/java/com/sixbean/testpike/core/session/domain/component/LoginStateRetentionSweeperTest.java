package com.sixbean.testpike.core.session.domain.component;

import com.sixbean.testpike.core.CoreApplication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Retention sweep for the login-state ledger the owner
 * registration that keeps {@code session.retention-days} from becoming a
 * seeded key without a loop, the in-book defect shape called out in the AV
 * design). Real slot6 database, zero mocks, idiom of MockHitRetentionSweeperTest.
 * <p>Behaviour under test:
 * <ul>
 * <li>disabled rows past the window go; disabled rows inside it and enabled
 * rows of any age stay (retention only sweeps failed entries);</li>
 * <li>the vault ciphertext of a swept row is purged only when it was
 * capture-produced (source_kind=3) and no other ledger row still
 * references it — manual/legacy vault rows are never touched;</li>
 * <li>a non-positive window refuses the sweep instead of purging all;</li>
 * <li>the sweep is idempotent: a second round settles nothing new.</li>
 * </ul>
 * Fixture rows ride a unique project so cleanup and assertions only ever
 * look at this test's own rows.
 */
@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = "testpike.scheduling.enabled=true")
class LoginStateRetentionSweeperTest {

    @Autowired
    private LoginStateRetentionSweeper sweeper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;

    @BeforeEach
    void setUp() {
        String code = "lrs" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "session-retention-" + code, code);
        projectId = jdbcTemplate.queryForObject("SELECT id FROM tp_project WHERE code = ?",
                Long.class, code);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM tp_login_state WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_credential WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
    }

    private long insertCredential(String credKey, int sourceKind) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO tp_credential (project_id, name, cred_key, secret_cipher, source_kind) "
                        + "VALUES (?, ?, ?, 'ENC:fixture-not-a-real-cipher', ?) RETURNING id",
                Long.class, projectId, credKey, credKey, sourceKind);
    }

    private long insertState(String stateName, int status, String ageInterval, Long credentialId) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO tp_login_state (project_id, state_name, kind, credential_id, "
                        + "status, updated_at) VALUES (?, ?, 'token', ?, ?, now() - interval '"
                        + ageInterval + "') RETURNING id",
                Long.class, projectId, stateName, credentialId, status);
    }

    private int countStates(String stateName) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_login_state WHERE project_id = ? AND state_name = ?",
                Integer.class, projectId, stateName);
    }

    private int countCredential(long id) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_credential WHERE id = ?", Integer.class, id);
    }

    @Test
    void sweepsOldDisabledRowsAndKeepsEverythingElse() {
        long deadCred = insertCredential("k-dead" + UUID.randomUUID(), 3);
        long oldDead = insertState("old-disabled", 0, "45 days", deadCred);
        long freshCred = insertCredential("k-fresh" + UUID.randomUUID(), 3);
        insertState("fresh-disabled", 0, "20 days", freshCred);
        long aliveCred = insertCredential("k-alive" + UUID.randomUUID(), 3);
        insertState("old-but-enabled", 1, "45 days", aliveCred);
        assertNotNull(oldDead);

        // swept counts globally (the sweep is a table-wide loop); assertions
        // below pin only this fixture's own rows.
        sweeper.sweepWithWindow(30);

        assertEquals(0, countStates("old-disabled"));
        assertEquals(1, countStates("fresh-disabled"));
        assertEquals(1, countStates("old-but-enabled"));
        assertEquals(0, countCredential(deadCred), "capture ciphertext past retention is purged");
        assertEquals(1, countCredential(freshCred), "inside-window row keeps its ciphertext");
        assertEquals(1, countCredential(aliveCred), "enabled row keeps its ciphertext");
    }

    @Test
    void sharedVaultRowSurvivesWhenAnotherLedgerRowStillReferencesIt() {
        long sharedCred = insertCredential("k-shared" + UUID.randomUUID(), 3);
        insertState("shared-dying", 0, "60 days", sharedCred);
        insertState("shared-holder", 1, "1 day", sharedCred);

        sweeper.sweepWithWindow(30);

        assertEquals(0, countStates("shared-dying"));
        assertEquals(1, countStates("shared-holder"));
        assertEquals(1, countCredential(sharedCred), "referenced vault row must survive the sweep");
    }

    @Test
    void legacyManualVaultRowsAreNeverPurgedByRetention() {
        long manualCred = insertCredential("k-manual" + UUID.randomUUID(), 1);
        insertState("manual-disabled-old", 0, "45 days", manualCred);

        sweeper.sweepWithWindow(30);

        assertEquals(0, countStates("manual-disabled-old"));
        assertEquals(1, countCredential(manualCred), "source_kind=1 rows are out of the sweep scope");
    }

    @Test
    void nonPositiveWindowRefusesInsteadOfPurgingAll() {
        insertState("refuse-check", 0, "400 days", null);

        assertEquals(0, sweeper.sweepWithWindow(0));
        assertEquals(0, sweeper.sweepWithWindow(-1));
        assertEquals(1, countStates("refuse-check"));
    }

    @Test
    void secondRoundSettlesNothingNew() {
        insertState("idem-old", 0, "90 days", insertCredential("k-idem" + UUID.randomUUID(), 3));
        assertEquals(1, countStates("idem-old"), "fixture seeded");

        assertTrue(sweeper.sweepWithWindow(30) >= 1, "first round settles the fresh fixture row");
        assertEquals(0, sweeper.sweepWithWindow(30), "second round settles nothing new");
    }
}
