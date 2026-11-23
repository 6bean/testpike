package com.sixbean.testpike.core.datafactory.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.datafactory.api.dto.DataFactoryCreateInput;
import com.sixbean.testpike.core.datafactory.api.dto.DataFactoryInfo;
import com.sixbean.testpike.core.datafactory.api.dto.DataFactoryUpdateInput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Optimistic-lock version ledger on data-factory script rows (AM batch
 * .
 * <p>
 * tp_data_factory is updated in place, so a dialog draft that proposes an
 * edit of an existing script needs a row version to re-anchor against
 * (V330 server-side anchor). Before this segment the table had no version
 * column at all. Suite pins the ledger against the real dev database —
 * rows are created through the service and cleaned by id (R37: factory
 * tables stay resident, nothing is truncated here).
 */
@SpringBootTest(classes = CoreApplication.class)
class DataFactoryRowVersionLedgerTest {

    @Autowired
    private DataFactoryService dataFactoryService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String tag;
    private long projectId;
    private long envId;
    private long userId;
    private long factoryId;

    @BeforeEach
    void setUp() {
        tag = "fvr" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        userId = jdbcTemplate.queryForObject("""
                INSERT INTO tp_user (username, password_hash, display_name, role, status)
                VALUES (?, '$2a$10$x', ?, 1, 1) RETURNING id
                """, Long.class, tag, tag);
        projectId = jdbcTemplate.queryForObject(
                "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1) RETURNING id",
                Long.class, tag, tag);
        envId = jdbcTemplate.queryForObject("""
                INSERT INTO tp_project_env (project_id, name, env_type, base_url)
                VALUES (?, ?, 2, 'http://tp-local.test') RETURNING id
                """, Long.class, projectId, tag);
        DataFactoryInfo created = dataFactoryService.create(userId, new DataFactoryCreateInput(
                projectId, envId, 3, "anchor script " + tag, "print('[]')", null));
        factoryId = created.id();
    }

    @AfterEach
    void tearDown() {
        if (factoryId > 0) {
            jdbcTemplate.update("DELETE FROM tp_data_factory WHERE id = ?", factoryId);
        }
        jdbcTemplate.update("DELETE FROM tp_data_factory_run WHERE factory_id = ?", factoryId);
        jdbcTemplate.update("DELETE FROM tp_project_env WHERE id = ?", envId);
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_user WHERE id = ?", userId);
    }

    private int versionOf() {
        Integer version = jdbcTemplate.queryForObject(
                "SELECT version FROM tp_data_factory WHERE id = ?", Integer.class, factoryId);
        assertNotNull(version, "a factory row carries an optimistic version");
        return version;
    }

    @Test
    void freshFactoryRowStartsAtVersionOne() {
        assertEquals(1, versionOf(),
                "the ledger starts at 1 so an echoed anchor of 1 means an untouched row");
    }

    @Test
    void updateBumpsTheRowVersion() {
        dataFactoryService.update(userId, factoryId,
                new DataFactoryUpdateInput("renamed " + tag, null, null, null));
        assertEquals(2, versionOf(),
                "a saved edit must advance the version ledger the dialog anchors on");
        dataFactoryService.update(userId, factoryId,
                new DataFactoryUpdateInput(null, "print([1,2])", null, null));
        assertEquals(3, versionOf(),
                "every write through the update chain bumps exactly once");
    }
}
