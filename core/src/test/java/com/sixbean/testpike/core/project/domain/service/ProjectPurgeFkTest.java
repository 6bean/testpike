package com.sixbean.testpike.core.project.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.support.FixtureInserts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;


@SpringBootTest(classes = CoreApplication.class)
class ProjectPurgeFkTest {

    @Autowired
    private ProjectService projectService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;
    private long pluginId;
    private long batchId;

    @BeforeEach
    void seedProject() {
        String code = "T268FK" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        jdbcTemplate.update("INSERT INTO tp_project (name, code) VALUES (?, ?)", "purge级联夹具", code);
        projectId = jdbcTemplate.queryForObject("SELECT id FROM tp_project WHERE code = ?", Long.class, code);

        jdbcTemplate.update("INSERT INTO tp_plugin (name, industry, version, manifest) "
                        + "VALUES (?, 'POS', '1.0.0', '{}'::jsonb)",
                "t268fk-plugin-" + code);
        pluginId = jdbcTemplate.queryForObject("SELECT id FROM tp_plugin WHERE name = ?", Long.class,
                "t268fk-plugin-" + code);

        jdbcTemplate.update("INSERT INTO tp_skill (project_id, name, type, deleted) VALUES (?, ?, 1, 1)",
                projectId, "t268fk-skill-" + code);
        long skillId = jdbcTemplate.queryForObject("SELECT id FROM tp_skill WHERE name = ?", Long.class,
                "t268fk-skill-" + code);
        jdbcTemplate.update("INSERT INTO tp_project_skill (project_id, skill_id) VALUES (?, ?)",
                projectId, skillId);
        long sessionId = FixtureInserts.insertReturningId(jdbcTemplate,
                "INSERT INTO tp_explore_session (project_id, goal_text, start_url, budget_snapshot) "
                        + "VALUES (?, '级联验证', 'http://x', '{}'::jsonb)", projectId);
        jdbcTemplate.update("INSERT INTO tp_explore_step (session_id, seq) VALUES (?, 1)", sessionId);
        jdbcTemplate.update("INSERT INTO tp_cost_budget (project_id, budget_cents) VALUES (?, 100)", projectId);
        jdbcTemplate.update("INSERT INTO tp_project_plugin (project_id, plugin_id, param_snapshot) "
                        + "VALUES (?, ?, '{}'::jsonb)", projectId, pluginId);
        jdbcTemplate.update("INSERT INTO tp_tool (project_id, name, type) VALUES (?, ?, 1)",
                projectId, "t268fk-tool-" + code);

        jdbcTemplate.update("INSERT INTO tp_apitest_collection (project_id, parent_id, name, tags) "
                        + "VALUES (?, 0, ?, '')", projectId, "t268fk-col-" + code);
        long collectionId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_apitest_collection WHERE project_id = ? AND name = ?",
                Long.class, projectId, "t268fk-col-" + code);
        jdbcTemplate.update("INSERT INTO tp_apitest_request (collection_id, name, method, url_path, "
                        + "headers_json, body_text, auth_json, assertions_json, created_by) "
                        + "VALUES (?, ?, 'GET', '/ping', '[]'::jsonb, '', '{}'::jsonb, '[]'::jsonb, 1)",
                collectionId, "t268fk-req-" + code);
        long requestId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_apitest_request WHERE collection_id = ? AND name = ?",
                Long.class, collectionId, "t268fk-req-" + code);
        jdbcTemplate.update("INSERT INTO tp_apitest_run (request_id, env_id, status, created_by) "
                + "VALUES (?, NULL, 0, 1)", requestId);
        // Collection-run ledger (V248): run_set holds a NO ACTION FK to the
        // collection and child runs hold a NO ACTION FK to run_set. The L3
        // probe proved a purge of a project with a run set fails with an FK
        // violation (HTTP 500) — this fixture locks the missing delete step.
        jdbcTemplate.update("INSERT INTO tp_apitest_run_set (collection_id, project_id, name, "
                        + "interruption_mode, status, pinned_json, total_steps) "
                        + "VALUES (?, ?, ?, 'STOP_ON_ASSERT_FAIL', 0, '[]'::jsonb, 1)",
                collectionId, projectId, "t369fk-set-" + code);
        long runSetId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_apitest_run_set WHERE project_id = ? AND name = ?",
                Long.class, projectId, "t369fk-set-" + code);
        jdbcTemplate.update("INSERT INTO tp_apitest_run (request_id, env_id, status, created_by, "
                        + "run_set_id, seq_no) VALUES (?, NULL, 0, 1, ?, 1)", requestId, runSetId);
        // Flow template : project FK NO ACTION would make a
        // purge of a project owning a template die on the FK (same accident
        // shape the run_set row proved in V248) — this fixture locks the
        // delete step. The provenance FK to the collection is SET NULL and
        // must never block; steps below assert both verdicts.
        jdbcTemplate.update("INSERT INTO tp_apitest_flow_template (project_id, name, "
                        + "source_collection_id, steps_snapshot_json) "
                        + "VALUES (?, ?, ?, '[{\"requestId\":1,\"name\":\"x\"}]'::jsonb)",
                projectId, "t414fk-tpl-" + code, collectionId);

        jdbcTemplate.update("INSERT INTO tp_project_env (project_id, name, env_type, base_url) "
                + "VALUES (?, ?, 2, 'http://localhost/purge-fk')", projectId, "t268fk-env-" + code);
        long envId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_project_env WHERE project_id = ? AND name = ?",
                Long.class, projectId, "t268fk-env-" + code);
        jdbcTemplate.update("INSERT INTO tp_perf_scenario (project_id, env_id, name, pressure_model, "
                        + "pressure_config, steps_json) VALUES (?, ?, ?, 'CONSTANT_RPS', "
                        + "'{\"targetRps\": 10}'::jsonb, '[]'::jsonb)",
                projectId, envId, "t268fk-sc-" + code);
        long scenarioId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_perf_scenario WHERE project_id = ? AND name = ?",
                Long.class, projectId, "t268fk-sc-" + code);
        jdbcTemplate.update("INSERT INTO tp_perf_whitelist (project_id, env_id, max_concurrency, allowed_modes) "
                + "VALUES (?, ?, 10, '[\"SHADOW_TABLE\"]'::jsonb)", projectId, envId);
        // The run id comes from the INSERT itself: re-querying the newest row
        // by ORDER BY id DESC would bind the fixture to another fixture's run
        // on a shared database.
        long runId = FixtureInserts.insertReturningId(jdbcTemplate,
                "INSERT INTO tp_perf_run (scenario_id, project_id, pressure_snapshot) "
                        + "VALUES (?, ?, '{}'::jsonb)", scenarioId, projectId);
        // A-13 vision fixture proved the accident shape: tp_perf_approval carries a
        // scenario_id FK onto tp_perf_scenario (the D8 auto-launch leg), so a purge
        // whose approval delete runs AFTER the scenario delete dies on
        // tp_perf_approval_scenario_id_fkey. This seed locks the corrected order.
        jdbcTemplate.update("INSERT INTO tp_perf_approval (project_id, env_id, requested_concurrency, "
                        + "isolation_mode, reason, applicant_id, status, scenario_id) "
                        + "VALUES (?, ?, 10, 'SHADOW_TABLE', 'purge-fk 审批夹具', "
                        + "(SELECT id FROM tp_user ORDER BY id LIMIT 1), 2, ?)",
                projectId, envId, scenarioId);
        // data pool: the CSV ledger row carries a NO ACTION project FK and
        // the scenario holds a soft reference (tp_perf_scenario.dataset_id) to
        // it, so a purge of a project owning a dataset dies on the FK unless the
        // delete list covers it (same accident shape the whitelist/approval rows
        // proved). Bind the scenario to the pool so both directions are seeded.
        jdbcTemplate.update("INSERT INTO tp_perf_dataset (project_id, name, file_name, object_key, "
                        + "columns_json, row_count, size_bytes) VALUES (?, ?, 't375fk.csv', "
                        + "'perf-dataset/t375fk', '[\"username\"]'::jsonb, 3, 42)",
                projectId, "t375fk-ds-" + code);
        long datasetId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_perf_dataset WHERE project_id = ? AND name = ?",
                Long.class, projectId, "t375fk-ds-" + code);
        jdbcTemplate.update("UPDATE tp_perf_scenario SET dataset_id = ? WHERE id = ?",
                datasetId, scenarioId);
        jdbcTemplate.update("INSERT INTO tp_perf_metric (run_id, project_id, second_offset, rps, p95_ms, error_rate) "
                + "VALUES (?, ?, 1, 10, 100, 0)", runId, projectId);
        jdbcTemplate.update("INSERT INTO tp_perf_sample (run_id, project_id, second_offset, event_kind, detail) "
                        + "VALUES (?, ?, 1, 'info', 'purge-fk 夹具')", runId, projectId);

        jdbcTemplate.update("INSERT INTO tp_appauto_device (project_id, name, serial, device_type) "
                        + "VALUES (?, ?, ?, 'USB')", projectId, "t276a-dev-" + code, "T276AFK" + code);
        long deviceId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_appauto_device WHERE project_id = ? AND name = ?",
                Long.class, projectId, "t276a-dev-" + code);
        jdbcTemplate.update("INSERT INTO tp_appauto_lease_run (device_id, project_id, holder_executor_id, "
                        + "state, acquired_at, expires_at) VALUES (?, ?, 'execFk', 'RELEASED', "
                        + "now() - interval '2 hour', now() - interval '1 hour')", deviceId, projectId);

        jdbcTemplate.update("INSERT INTO tp_appauto_popup_rule (project_id, title, rule, status, source) "
                        + "VALUES (?, ?, '{\"detect\":{\"textContains\":\"更新\"},"
                        + "\"dismiss\":{\"action\":\"app.tap\",\"target\":{\"text\":\"以后再说\"}}}'::jsonb, 1, 'AI')",
                projectId, "t943fk-popup-" + code);

        jdbcTemplate.update("INSERT INTO tp_credential (project_id, name, cred_key, secret_cipher) "
                        + "VALUES (?, ?, ?, 'ENC:purge-fk-placeholder')",
                projectId, "t924fk-cred-" + code, "t924fk" + code);
        // Login-state ledger (V502): tp_login_state carries NO ACTION FKs to both
        // tp_credential and the project, so the purge must delete the ledger row
        // BEFORE its vault row. The L4 fuse window proved a purge of a project
        // owning login states dies on tp_login_state_credential_id_fkey (500).
        long vaultId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_credential WHERE project_id = ? AND name = ?",
                Long.class, projectId, "t924fk-cred-" + code);
        jdbcTemplate.update("INSERT INTO tp_login_state (project_id, state_name, kind, credential_id, "
                        + "scope_origins, source_kind) VALUES (?, ?, 'token', ?, '[]'::jsonb, 3)",
                projectId, "purge-fk-state-" + code, vaultId);

        jdbcTemplate.update("INSERT INTO tp_exec_batch (project_id, name, status) VALUES (?, ?, 2)",
                projectId, "t1038fk-batch-" + code);
        batchId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_exec_batch WHERE project_id = ? AND name = ?",
                Long.class, projectId, "t1038fk-batch-" + code);
        jdbcTemplate.update("INSERT INTO tp_batch_param_snapshot (batch_id, gen, params_json) "
                + "VALUES (?, 1, '{}')", batchId);
        jdbcTemplate.update("INSERT INTO tp_batch_runtime_change (batch_id, kind) VALUES (?, 'start')", batchId);

        jdbcTemplate.update("INSERT INTO tp_exec_task (project_id, case_id, status, task_type) "
                + "VALUES (?, 0, 1, 5)", projectId);

        // AH closing journey proved these family tables were missing from
        // the purge path: force-deleting a project that owns contract rows fails
        // on tp_api_spec_preview_project_id_fkey (NO ACTION), and tp_api_spec /
        // tp_api_spec_change_analysis carry no FK at all, so they would linger as
        // orphans forever. The subscription / step-alias / appauto rows are the
        // same root cause (project FK with NO ACTION) found by the FK census.
        jdbcTemplate.update("INSERT INTO tp_api_spec (project_id, name, spec_format, spec_title, "
                        + "spec_text, content_sha256, spec_version, endpoints) "
                        + "VALUES (?, ?, 'openapi3-json', 'purge级联契约', '{}', 'sha-purge-fk', 1, '[]'::jsonb)",
                projectId, "t373fk-spec-" + code);
        long specId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_api_spec WHERE project_id = ? AND name = ?",
                Long.class, projectId, "t373fk-spec-" + code);
        jdbcTemplate.update("INSERT INTO tp_api_spec_preview (project_id, spec_id, items, applied_keys, "
                        + "status) VALUES (?, ?, '[]'::jsonb, '[]'::jsonb, 'pending')",
                projectId, specId);
        jdbcTemplate.update("INSERT INTO tp_api_spec_change_analysis (project_id, old_spec_id, new_spec_id, "
                        + "changes, detected_source, status) VALUES (?, ?, ?, '[]'::jsonb, 'upload', 0)",
                projectId, specId, specId);
        jdbcTemplate.update("INSERT INTO tp_spec_subscription (project_id, name, url, "
                        + "check_interval_minutes, enabled) VALUES (?, ?, ?, 60, true)",
                projectId, "t373fk-sub-" + code, "http://127.0.0.1:8899/spec.json");
        jdbcTemplate.update("INSERT INTO tp_step_alias (project_id, alias_name, kind, target) "
                        + "VALUES (?, ?, 'step', '{}')", projectId, "t373fk-alias-" + code);
        jdbcTemplate.update("INSERT INTO tp_appauto_env_profile (project_id, name, profile_kind, payload) "
                        + "VALUES (?, ?, 'ANDROID_SDK', '{}')", projectId, "t373fk-envp-" + code);
        jdbcTemplate.update("INSERT INTO tp_appauto_record_draft (project_id, case_id, event_source, items, "
                        + "status) VALUES (?, 0, 'scrcpy', '[]'::jsonb, 1)", projectId);
        long holderUserId = jdbcTemplate.queryForObject("SELECT min(id) FROM tp_user", Long.class);
        jdbcTemplate.update("INSERT INTO tp_appauto_reservation (project_id, device_id, holder_user_id, "
                        + "starts_at, ends_at, state, reason) VALUES (?, ?, ?, now(), now() + interval '1 hour', "
                        + "'ACTIVE', 'purge-fk 夹具')", projectId, deviceId, holderUserId);
        jdbcTemplate.update("INSERT INTO tp_appauto_takeover_request (device_id, project_id, requester_id, "
                        + "occupier_id, state, expires_at) VALUES (?, ?, ?, ?, 'pending', "
                        + "now() + interval '1 hour')", deviceId, projectId, holderUserId, holderUserId);
    }

    @AfterEach
    void cleanFixture() {
        if (projectId == 0) {
            return;
        }
        cleanByProject(projectId);
        jdbcTemplate.update("DELETE FROM tp_plugin WHERE id = ?", pluginId);
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
    }

    private void cleanByProject(long pid) {
        jdbcTemplate.update("DELETE FROM tp_explore_step WHERE session_id IN "
                + "(SELECT id FROM tp_explore_session WHERE project_id = ?)", pid);
        for (String sql : new String[]{
                "DELETE FROM tp_project_skill WHERE project_id = ?",
                "DELETE FROM tp_skill WHERE project_id = ?",
                "DELETE FROM tp_explore_session WHERE project_id = ?",
                "DELETE FROM tp_cost_budget WHERE project_id = ?",
                "DELETE FROM tp_project_plugin WHERE project_id = ?",
                "DELETE FROM tp_tool WHERE project_id = ?"}) {
            jdbcTemplate.update(sql, pid);
        }
        jdbcTemplate.update("DELETE FROM tp_apitest_run WHERE request_id IN "
                        + "(SELECT r.id FROM tp_apitest_request r JOIN tp_apitest_collection c ON c.id = r.collection_id WHERE c.project_id = ?)",
                pid);
        // Collection-run ledger (V248): deleted after runs, before requests —
        // same order as the purge product path; fixture failure-path recovery.
        jdbcTemplate.update("DELETE FROM tp_apitest_run_set WHERE project_id = ?", pid);
        // Flow templates (V265): go with the project, before collections —
        // same order as the purge product path; fixture failure-path recovery.
        jdbcTemplate.update("DELETE FROM tp_apitest_flow_template WHERE project_id = ?", pid);
        jdbcTemplate.update("DELETE FROM tp_apitest_request WHERE collection_id IN "
                        + "(SELECT id FROM tp_apitest_collection WHERE project_id = ?)", pid);
        jdbcTemplate.update("DELETE FROM tp_apitest_collection WHERE project_id = ?", pid);
        jdbcTemplate.update("DELETE FROM tp_perf_metric WHERE project_id = ?", pid);
        jdbcTemplate.update("DELETE FROM tp_perf_sample WHERE project_id = ?", pid);
        jdbcTemplate.update("DELETE FROM tp_perf_run WHERE project_id = ?", pid);
        // A-13: the approval ledger references the scenario — it must leave first,
        // same corrected order as the purge product path.
        jdbcTemplate.update("DELETE FROM tp_perf_approval WHERE project_id = ?", pid);
        jdbcTemplate.update("DELETE FROM tp_perf_scenario WHERE project_id = ?", pid);
        // data pool: scenario first (soft dataset_id reference), then the
        // ledger row; failure-path recovery mirrors the purge product order.
        jdbcTemplate.update("DELETE FROM tp_perf_dataset WHERE project_id = ?", pid);
        jdbcTemplate.update("DELETE FROM tp_perf_whitelist WHERE project_id = ?", pid);
        // The takeover/reservation rows reference the device, so on the failure
        // path they must leave before it (proved by a suppressed FK error on
        // tp_appauto_takeover_request_device_id_fkey while this test was red).
        jdbcTemplate.update("DELETE FROM tp_appauto_takeover_request WHERE project_id = ?", pid);
        jdbcTemplate.update("DELETE FROM tp_appauto_reservation WHERE project_id = ?", pid);
        jdbcTemplate.update("DELETE FROM tp_appauto_lease_run WHERE project_id = ?", pid);
        jdbcTemplate.update("DELETE FROM tp_appauto_device WHERE project_id = ?", pid);
        jdbcTemplate.update("DELETE FROM tp_appauto_popup_rule WHERE project_id = ?", pid);
        // Login-state ledger leaves before the vault row — same child→parent order
        // as the corrected purge path (FK census V502 ledger row).
        jdbcTemplate.update("DELETE FROM tp_login_state WHERE project_id = ?", pid);
        jdbcTemplate.update("DELETE FROM tp_credential WHERE project_id = ?", pid);
        jdbcTemplate.update("DELETE FROM tp_batch_param_snapshot WHERE batch_id IN "
                + "(SELECT id FROM tp_exec_batch WHERE project_id = ?)", pid);
        jdbcTemplate.update("DELETE FROM tp_batch_runtime_change WHERE batch_id IN "
                + "(SELECT id FROM tp_exec_batch WHERE project_id = ?)", pid);
        jdbcTemplate.update("DELETE FROM tp_exec_task WHERE project_id = ?", pid);
        jdbcTemplate.update("DELETE FROM tp_exec_batch WHERE project_id = ?", pid);
        // census fixtures (contract family + alias + appauto): failure-path
        // recovery, delete order child → parent, same as the purge product path.
        jdbcTemplate.update("DELETE FROM tp_api_spec_preview WHERE project_id = ?", pid);
        jdbcTemplate.update("DELETE FROM tp_api_spec_change_analysis WHERE project_id = ?", pid);
        jdbcTemplate.update("DELETE FROM tp_spec_subscription WHERE project_id = ?", pid);
        jdbcTemplate.update("DELETE FROM tp_step_alias WHERE project_id = ?", pid);
        jdbcTemplate.update("DELETE FROM tp_appauto_takeover_request WHERE project_id = ?", pid);
        jdbcTemplate.update("DELETE FROM tp_appauto_reservation WHERE project_id = ?", pid);
        jdbcTemplate.update("DELETE FROM tp_appauto_record_draft WHERE project_id = ?", pid);
        jdbcTemplate.update("DELETE FROM tp_appauto_env_profile WHERE project_id = ?", pid);
        jdbcTemplate.update("DELETE FROM tp_api_spec WHERE project_id = ?", pid);
        jdbcTemplate.update("DELETE FROM tp_project_env WHERE project_id = ?", pid);
    }

    private long count(String table) {
        Long n = jdbcTemplate.queryForObject("SELECT count(*) FROM " + table + " WHERE project_id = ?",
                Long.class, projectId);
        return n == null ? 0 : n;
    }

    @Test
    void purge_级联删除软删SKILL与环境工具预算探索插件_不炸FK且项目零行() {
        assertDoesNotThrow(() -> projectService.purge(1L, projectId, true),
                "purge force 应吞掉全部项目 FK 子表，不得因 tp_skill/tp_explore_step 等残留炸 FK 违例");

        assertEquals(0, count("tp_skill"), "含软删（deleted=1）SKILL 一并物理删");
        assertEquals(0, count("tp_project_skill"));
        assertEquals(0, count("tp_explore_session"));
        assertEquals(0, count("tp_cost_budget"));
        assertEquals(0, count("tp_project_plugin"));
        assertEquals(0, count("tp_tool"));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_explore_step WHERE session_id IN "
                        + "(SELECT id FROM tp_explore_session WHERE project_id = ?)",
                Long.class, projectId), "探索步骤子表不得留孤儿");
        assertEquals(0, count("tp_apitest_collection"), "W1 集合表缺项实证（tick520 L3 FK 违例）防再犯");
        assertEquals(0, count("tp_apitest_flow_template"), "V265 流程模板表缺项防线（T414 片4 立表即锁）");
        assertEquals(0, count("tp_perf_scenario"), "W4 场景表缺项实证（tick658 E2E purge 500）防再犯");
        assertEquals(0, count("tp_perf_dataset"),
                "T375 CSV 数据池账本缺项防线（项目 FK NO ACTION + 场景软引用双向）");
        assertEquals(0, count("tp_perf_run"));
        assertEquals(0, count("tp_perf_metric"));
        assertEquals(0, count("tp_perf_sample"));
        assertEquals(0, count("tp_perf_whitelist"));
        assertEquals(0, count("tp_perf_approval"),
                "A-13 审批单挂场景 FK 的 purge 删序防线（审批先删，反序炸 tp_perf_approval_scenario_id_fkey）");
        assertEquals(0, count("tp_appauto_device"), "W5 设备档案表缺项实证（tick679 L3 FK 违例）防再犯");
        assertEquals(0, count("tp_appauto_lease_run"));
        assertEquals(0, count("tp_appauto_popup_rule"), "弹窗规则草稿表缺项实证（T327 包④实查 FK NO ACTION）防再犯");
        assertEquals(0, count("tp_credential"), "凭据保险箱表缺项实证（tick924 E2E FK 违例）防再犯");
        assertEquals(0, count("tp_login_state"),
                "Login-state ledger (V502) missing from the purge path: the L4 fuse window "
                        + "proved purge of a project holding login states fails on "
                        + "tp_login_state_credential_id_fkey with an FK violation (500)");
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_batch_param_snapshot WHERE batch_id IN "
                        + "(SELECT id FROM tp_exec_batch WHERE project_id = ?)",
                Long.class, projectId),
                "批次参数快照表缺项实证（运行日志=DELETE tp_exec_batch 炸 FK 违例 500）防再犯");
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_batch_runtime_change WHERE batch_id IN "
                        + "(SELECT id FROM tp_exec_batch WHERE project_id = ?)",
                Long.class, projectId), "批次运行时变更表同因缺项防再犯");
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_exec_task WHERE batch_id IN "
                        + "(SELECT id FROM tp_exec_batch WHERE project_id = ?)",
                Long.class, projectId), "批次从属任务须清零");
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_exec_task WHERE project_id = ? AND batch_id IS NULL",
                Long.class, projectId), "项目直挂任务（batch_id NULL）两头口径罩不住=池视图残留永红实证");
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_apitest_request r JOIN tp_apitest_collection c "
                        + "ON c.id = r.collection_id WHERE c.project_id = ?",
                Long.class, projectId), "W1 请求表零行");
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_apitest_run WHERE request_id IN "
                        + "(SELECT r.id FROM tp_apitest_request r JOIN tp_apitest_collection c "
                        + "ON c.id = r.collection_id WHERE c.project_id = ?)",
                Long.class, projectId), "W1 运行表零行（两级子查询删序勿反）");
        assertEquals(0, count("tp_apitest_run_set"),
                "Collection-run ledger (V248) missing from the purge path: L3 probe proved "
                        + "purge of a project holding a run set fails with an FK violation");
        assertEquals(0, count("tp_api_spec"),
                "契约本体表缺项实证（T373 L4 旅程=软引用无 FK，项目删后孤儿永红）防再犯");
        assertEquals(0, count("tp_api_spec_preview"),
                "契约预览表缺项实证（T373 L4 旅程 purge 炸 tp_api_spec_preview_project_id_fkey）防再犯");
        assertEquals(0, count("tp_api_spec_change_analysis"),
                "变更分析队列表缺项实证（同族软引用）防再犯");
        assertEquals(0, count("tp_spec_subscription"),
                "契约订阅表缺项实证（挂项目 FK NO ACTION）防再犯");
        assertEquals(0, count("tp_step_alias"), "步骤别名表缺项实证（挂项目 FK NO ACTION）防再犯");
        assertEquals(0, count("tp_appauto_env_profile"),
                "APP 环境档案表缺项实证（挂项目 FK NO ACTION）防再犯");
        assertEquals(0, count("tp_appauto_record_draft"),
                "APP 录制草稿表缺项实证（挂项目 FK NO ACTION）防再犯");
        assertEquals(0, count("tp_appauto_reservation"),
                "设备预约表缺项实证（挂项目 FK NO ACTION）防再犯");
        assertEquals(0, count("tp_appauto_takeover_request"),
                "设备接管申请表缺项实证（挂项目 FK NO ACTION）防再犯");
    }
}
