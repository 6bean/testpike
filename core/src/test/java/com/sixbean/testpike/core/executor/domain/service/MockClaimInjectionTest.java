package com.sixbean.testpike.core.executor.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.executor.api.dto.ClaimTaskView;
import com.sixbean.testpike.core.executor.api.dto.ExecTaskCreateInput;
import com.sixbean.testpike.core.executor.api.dto.ExecutorRegisterInput;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import com.sixbean.testpike.core.mock.api.dto.MockServingBinding;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Claim-side mock binding injection for the browser batch face.
 * <p>Contract (03 2.3 trailing key): a task_type=1 (browser case) claim whose
 * case declares a mock space AND that space is PUBLISHED carries
 * {@code mock={spaceKey,baseUrl,snapshotVersion}} on the claim view. The
 * binding gate, address composition and version pin all come from
 * MockServingBindingResolver — the exact single source the api-test envelope
 * assembly already uses (red line 6, no second copy).
 * <p>Zero-drift spectrum: unbound case, draft/retired space, non-browser task
 * types and a null case id = the key stays null (old claim shape untouched —
 * the executor only routes traffic when the key exists).
 * <p>The authoritative envBaseUrl is delivered byte-for-byte: rewriting the
 * target toward the traffic plane is the executor's step, never the guard
 * input here (production-blacklist decisions read the authoritative env).
 * <p>Real dev database, zero mocks; fixture-owned rows cleaned in finally.
 */
@SpringBootTest(classes = CoreApplication.class)
class MockClaimInjectionTest {

    @Autowired
    private ExecTaskService execTaskService;
    @Autowired
    private ExecutorService executorService;
    @Autowired
    private ConfigCacheHolder configCacheHolder;
    @Autowired
    private JdbcTemplate jdbc;

    // Claim picks the oldest pending task, so this suite's crashed-run residue must
    // not hijack the queue head. Fixture-scoped teardown : sweep the
    // 't364claim-*' project families through the shared FK-tree helper (tasks, envs,
    // cases and mock spaces fall with the project) and drop 'ex-mock-%' executors by
    // name — the shared tp_exec_task/tp_executor tables are never blanket-truncated.
    @org.junit.jupiter.api.BeforeEach
    @org.junit.jupiter.api.AfterEach
    void cleanTables() {
        SharedDbFixtureSweep.byProjectList(jdbc, jdbc.queryForList(
                "SELECT id FROM tp_project WHERE name LIKE 't364claim-%'", Long.class));
        jdbc.update("DELETE FROM tp_executor WHERE name LIKE 'ex-mock-%'");
    }

    private record Fixture(long projectId, long envId, long caseId, long spaceId,
                           String spaceKey) {
    }

    /** Case with mock_space_id set when {@code bindSpace}=true (space row created with {@code status}). */
    private Fixture createFixture(boolean bindSpace, int status) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        jdbc.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "t364claim-" + suffix, "t364c" + suffix);
        long projectId = jdbc.queryForObject("SELECT id FROM tp_project WHERE code = ?",
                Long.class, "t364c" + suffix);
        long envId = jdbc.queryForObject("INSERT INTO tp_project_env (project_id, name, base_url, "
                        + "config_json) VALUES (?, ?, ?, '{}'::jsonb) RETURNING id",
                Long.class, projectId, "env-" + suffix, "http://shop-" + projectId + ".test");
        long spaceId = 0L;
        String spaceKey = null;
        if (bindSpace) {
            spaceKey = "t364ck" + suffix;
            spaceId = jdbc.queryForObject("INSERT INTO tp_mock_space (project_id, name, space_key, "
                            + "status) VALUES (?, ?, ?, ?) RETURNING id",
                    Long.class, projectId, "t364claim-" + suffix, spaceKey, status);
        }
        Long mockRef = spaceId == 0L ? null : spaceId;
        long caseId = jdbc.queryForObject("INSERT INTO tp_test_case (case_no, project_id, title, "
                        + "mock_space_id) VALUES (?, ?, ?, ?) RETURNING id",
                Long.class, "T364CLM-" + UUID.randomUUID().toString().substring(0, 20),
                projectId, "mock-bound browser case", mockRef);
        execTaskService.create(new ExecTaskCreateInput(projectId, caseId, "v2",
                "{\"steps\":[]}", envId));
        return new Fixture(projectId, envId, caseId, spaceId, spaceKey);
    }

    private ClaimTaskView claimOne() {
        long exId = executorService.register(new ExecutorRegisterInput(
                "ex-mock-" + System.nanoTime(), null, null, "web", "chromium", 2, "1.0.0", 0));
        ClaimTaskView view = execTaskService.claim(exId);
        assertNotNull(view, "a pending task must be claimable");
        return view;
    }

    private void cleanup(Fixture f) {
        jdbc.update("DELETE FROM tp_exec_task WHERE project_id = ?", f.projectId());
        jdbc.update("DELETE FROM tp_test_case WHERE id = ?", f.caseId());
        if (f.spaceId() != 0L) {
            jdbc.update("DELETE FROM tp_mock_space WHERE id = ?", f.spaceId());
        }
        jdbc.update("DELETE FROM tp_project_env WHERE id = ?", f.envId());
        jdbc.update("DELETE FROM tp_user_project WHERE project_id = ?", f.projectId());
        jdbc.update("DELETE FROM tp_project WHERE id = ?", f.projectId());
    }

    @Test
    void published_space_binding_appends_mock_key_on_claim() {
        String oldPublic = configCacheHolder.get("mock.public-base-url");
        String oldPath = configCacheHolder.get("mock.traffic.base-path");
        Fixture f = createFixture(true, 1);
        try {
            configCacheHolder.put("mock.public-base-url", "http://mock.example:18086");
            configCacheHolder.put("mock.traffic.base-path", "/mocks");

            ClaimTaskView view = claimOne();
            MockServingBinding mock = view.mock();
            assertNotNull(mock, "published binding must surface on the claim view");
            assertEquals(f.spaceKey, mock.spaceKey());
            assertEquals("http://mock.example:18086/mocks/" + f.spaceKey, mock.baseUrl(),
                    "baseUrl = public base + traffic prefix + space key (resolver single source)");
            assertNotNull(mock.snapshotVersion(), "version pin must come from the snapshot service");
            // authoritative env untouched — rewrite is the executor's step
            assertEquals("http://shop-" + f.projectId() + ".test", view.envBaseUrl());
            assertEquals(1, view.taskType(), "browser case claim is the injection subject");
        } finally {
            restore(oldPublic, oldPath);
            cleanup(f);
        }
    }

    @Test
    void draft_space_binding_emits_no_mock_key() {
        Fixture f = createFixture(true, 0);
        try {
            ClaimTaskView view = claimOne();
            assertNull(view.mock(), "draft space serves nothing = no key (old shape untouched)");
        } finally {
            cleanup(f);
        }
    }

    @Test
    void unbound_case_emits_no_mock_key() {
        Fixture f = createFixture(false, 0);
        try {
            ClaimTaskView view = claimOne();
            assertNull(view.mock(), "unbound case = no key, zero-drift claim shape");
        } finally {
            cleanup(f);
        }
    }

    private void restore(String oldPublic, String oldPath) {
        if (oldPublic == null) {
            configCacheHolder.remove("mock.public-base-url");
        } else {
            configCacheHolder.put("mock.public-base-url", oldPublic);
        }
        if (oldPath == null) {
            configCacheHolder.remove("mock.traffic.base-path");
        } else {
            configCacheHolder.put("mock.traffic.base-path", oldPath);
        }
    }
}
