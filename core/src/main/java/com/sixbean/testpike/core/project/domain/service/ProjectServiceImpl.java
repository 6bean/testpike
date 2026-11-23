package com.sixbean.testpike.core.project.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.core.audit.domain.component.Audit;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.events.api.dto.CaseEventMessage;
import com.sixbean.testpike.core.events.api.dto.CaseEventType;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.api.dto.ProjectInfo;
import com.sixbean.testpike.core.project.api.dto.ProjectUpdateInput;


import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.common.api.dto.DeleteCheckResult;
import com.sixbean.testpike.core.common.domain.component.DeleteGuard;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;


@Service
public class ProjectServiceImpl implements ProjectService {

    private static final String SELECT_COLUMNS = """
            p.id, p.name, p.code, p.default_lang, p.default_priority, p.status,
            p.budget_monthly, p.created_by, p.created_at, p.updated_at,
            p.jira_project_id, p.test_owner_id, p.dev_owner_id,
            p.script_storage_path, p.recording_storage_path, p.storage_mode,
            p.kb_enabled, p.notify_feishu, p.classify_vision, p.pool_quota_pct,
            p.ai_page_inspect_enabled,
            (SELECT up.role FROM tp_user_project up
              WHERE up.project_id = p.id AND up.user_id = ?) AS viewer_role
            """;

 
    private static final java.util.regex.Pattern JIRA_KEY_PATTERN =
            java.util.regex.Pattern.compile("[A-Z][A-Z0-9]{1,9}");

    private final JdbcTemplate jdbcTemplate;
    private final DeleteGuard deleteGuard;

 
    private final com.sixbean.testpike.core.cases.domain.component.CasePurgeSupport casePurgeSupport;

 
    private final com.sixbean.testpike.core.project.domain.component.ProjectRoleResolver roleResolver;

 
    private final com.sixbean.testpike.core.events.domain.service.CaseEventPublisher caseEventPublisher;

 
    private final ProjectTemplateService projectTemplateService;

 /** Chat ledger cascade piece (conversation sessions/messages/frames/cards
     *  carry the project anchor without any foreign key, so the purge list
     *  has to name them — see ChatLedgerPurgeSupport). */
    private final com.sixbean.testpike.core.aichat.domain.component.ChatLedgerPurgeSupport chatLedgerPurge;

    public ProjectServiceImpl(JdbcTemplate jdbcTemplate, DeleteGuard deleteGuard,
                              com.sixbean.testpike.core.cases.domain.component.CasePurgeSupport casePurgeSupport,
                              com.sixbean.testpike.core.project.domain.component.ProjectRoleResolver roleResolver,
                              com.sixbean.testpike.core.events.domain.service.CaseEventPublisher caseEventPublisher,
                              ProjectTemplateService projectTemplateService,
                              com.sixbean.testpike.core.aichat.domain.component.ChatLedgerPurgeSupport chatLedgerPurge) {
        this.jdbcTemplate = jdbcTemplate;
        this.deleteGuard = deleteGuard;
        this.casePurgeSupport = casePurgeSupport;
        this.roleResolver = roleResolver;
        this.caseEventPublisher = caseEventPublisher;
        this.projectTemplateService = projectTemplateService;
        this.chatLedgerPurge = chatLedgerPurge;
    }

    
    public PageResult<ProjectInfo> list(long viewerId, boolean allVisible, String keyword,
                                        Integer status, int page, int size) {
        return list(viewerId, allVisible, keyword, status, page, size, null, null);
    }

    /**
 * Sort whitelist for the project list . Values are physical
 * columns of tp_project without the alias prefix; the map output is the only
 * thing reaching the ORDER BY clause, enforced fail-closed by the shared
 * sort-column gate. status is out on purpose: the page renders no status
 * column (default list is active-only), and the viewer_role subquery column
 * must never be orderable — it is not a base-table column.
 */
    private static final java.util.Map<String, String> SORT_COLUMNS = java.util.Map.of(
            "id", "id",
            "name", "name",
            "code", "code",
            "created_at", "created_at");

    /**
     * ORDER BY builder for the project window: a null/blank key keeps the
     * historical created_at DESC,id DESC default; a whitelisted key orders by
     * that column (always alias-prefixed, the query reads FROM tp_project p)
     * with the constant id DESC tie-break pinned; an off-list key is refused
     * fail-closed by the gate.
     */
    private String orderClause(String sortBy, String sortDir) {
        String column = com.sixbean.testpike.core.common.domain.component.SortColumnPolicy
                .require(sortBy, SORT_COLUMNS, "项目");
        if (column == null) {
            return " ORDER BY p.created_at DESC, p.id DESC";
        }
        return " ORDER BY p." + column
                + ("asc".equalsIgnoreCase(sortDir) ? " ASC" : " DESC")
                + ", p.id DESC";
    }

    @Override
    public PageResult<ProjectInfo> list(long viewerId, boolean allVisible, String keyword,
                                        Integer status, int page, int size,
                                        String sortBy, String sortDir) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        int offset = (safePage - 1) * safeSize;

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (!allVisible) {
            where.append(" AND EXISTS (SELECT 1 FROM tp_user_project up2"
                    + " WHERE up2.project_id = p.id AND up2.user_id = ?)");
            args.add(viewerId);
        }
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (p.name ILIKE ? OR p.code ILIKE ?)");
            String kw = "%" + keyword.trim() + "%";
            args.add(kw);
            args.add(kw);
        }
        if (status != null) {
            where.append(" AND p.status = ?");
            args.add(status);
        }

        Long total = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_project p" + where, Long.class, args.toArray());

        List<Object> listArgs = new ArrayList<>();
        listArgs.add(viewerId);
        listArgs.addAll(args);
        listArgs.add(safeSize);
        listArgs.add(offset);
        List<ProjectInfo> list = jdbcTemplate.query(
                "SELECT " + SELECT_COLUMNS + " FROM tp_project p" + where
                        + orderClause(sortBy, sortDir) + " LIMIT ? OFFSET ?",
                this::mapRow, listArgs.toArray());
        return new PageResult<>(list, total != null ? total : 0L);
    }

    
    public ProjectInfo get(long projectId) {
        return selectById(projectId, 0L);
    }

    
    public ProjectInfo getWithViewer(long projectId, long viewerId) {
        return selectById(projectId, viewerId);
    }

    private ProjectInfo selectById(long projectId, long viewerId) {
        List<ProjectInfo> rows = jdbcTemplate.query(
                "SELECT " + SELECT_COLUMNS + " FROM tp_project p WHERE p.id = ?",
                this::mapRow, viewerId, projectId);
        if (rows.isEmpty()) {
            throw new ServiceException("项目 %d 不存在或已被删除，请前往「项目管理」重新选择项目",
                    BizErrorCode.NOT_FOUND, projectId);
        }
        return rows.get(0);
    }

    
    @Audit(objectType = "project", action = "create", objectId = "#result ?: #input.code()", after = "#result")
    @Transactional
    public long create(Long createdBy, ProjectCreateInput input) {
        String name = trimToNull(input.name());
        String code = trimToNull(input.code());
        if (name == null || name.length() > 100) {
            throw new ServiceException("项目名称不能为空且不超过 100 字，请在「新建项目」弹窗重新填写项目名称",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (code == null || code.length() > 20) {
            throw new ServiceException("项目编码不能为空且不超过 20 字符，请在「新建项目」弹窗按正确格式填写（如 TP-001）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        String lang = input.defaultLang() == null || input.defaultLang().isBlank()
                ? "zh-CN" : input.defaultLang().trim();
        try {
            long id = Objects.requireNonNull(jdbcTemplate.queryForObject("""
                    INSERT INTO tp_project (name, code, default_lang, default_priority, budget_monthly, created_by)
                    VALUES (?, ?, ?, ?, ?, ?) RETURNING id
                    """, Long.class, name, code, lang,
                    trimToNull(input.defaultPriority()), input.budgetMonthly(), createdBy));
            jdbcTemplate.update(
                    "INSERT INTO tp_user_project (project_id, user_id, role) VALUES (?, ?, 2)",
                    id, createdBy);
            if (input.templateId() != null) {
                projectTemplateService.applyToProject(input.templateId(), createdBy, id);
            }
            return id;
        } catch (DuplicateKeyException e) {
            throw new ServiceException(
                    "项目编码 %s 已存在，请在「新建项目」弹窗改成未被占用的编码（如 TP-001）后重试",
                    BizErrorCode.RESOURCE_CONFLICT, code);
        }
    }

 
    @Audit(objectType = "project", action = "update", objectId = "#projectId", after = "#result")
    public ProjectInfo update(long projectId, ProjectUpdateInput input) {
        get(projectId);
        String name = trimToNull(input.name());
        if (name == null || name.length() > 100) {
            throw new ServiceException("项目名称不能为空且不超过 100 字，请在「编辑项目」弹窗重新填写项目名称",
                    BizErrorCode.VALIDATION_ERROR);
        }
        String jiraKey = trimToNull(input.jiraProjectId());
        if (jiraKey != null && !JIRA_KEY_PATTERN.matcher(jiraKey).matches()) {
            throw new ServiceException(
                    "JIRA 项目键格式非法（仅大写字母开头，字母数字 2~10 位），请在「编辑项目」的「项目属性」区改成正确写法（如 TP2026）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        Long testOwner = input.testOwnerId();
        Long devOwner = input.devOwnerId();
        assertUserExists(testOwner, "测试负责人");
        assertUserExists(devOwner, "开发负责人");
        String scriptPath = trimToNull(input.scriptStoragePath());
        String recordingPath = trimToNull(input.recordingStoragePath());
        assertStoragePath(scriptPath, "测试脚本存放路径");
        assertStoragePath(recordingPath, "录制回放存放路径");
        Integer mode = input.storageMode();
        if (mode != null && (mode < 1 || mode > 3)) {
            throw new ServiceException("存储模式仅支持 1=系统默认 2=本地 3=云，请在「编辑项目」的「项目属性」区改成三者之一",
                    BizErrorCode.VALIDATION_ERROR);
        }
        Integer kbEnabled = input.kbEnabled();
        if (kbEnabled != null && (kbEnabled < 0 || kbEnabled > 1)) {
            throw new ServiceException("知识库开关仅接受 0=关 1=开，请在「编辑项目」的「项目属性」区改成 0 或 1",
                    BizErrorCode.VALIDATION_ERROR);
        }
        Integer notifyFeishu = input.notifyFeishu();
        if (notifyFeishu != null && (notifyFeishu < 0 || notifyFeishu > 1)) {
            throw new ServiceException("飞书群通知开关仅接受 0=仅站内 1=开，请在「编辑项目」的「项目属性」区改成 0 或 1",
                    BizErrorCode.VALIDATION_ERROR);
        }
        Integer poolQuotaPct = input.poolQuotaPct();
        if (poolQuotaPct != null && (poolQuotaPct < 1 || poolQuotaPct > 100)) {
            throw new ServiceException(
                    "资源池并发配额仅支持 1~100 的百分比（留空=不限），请在「编辑项目」的「项目属性」区改成该区间内的数值或留空",
                    BizErrorCode.VALIDATION_ERROR);
        }
        Integer pageInspectEnabled = input.pageInspectEnabled();
        if (pageInspectEnabled != null && (pageInspectEnabled < 0 || pageInspectEnabled > 1)) {
            throw new ServiceException("页面探查开关仅接受 0=关 1=开，请在「编辑项目」的「项目属性」区改成 0 或 1",
                    BizErrorCode.VALIDATION_ERROR);
        }
        jdbcTemplate.update("""
                UPDATE tp_project SET name = ?, default_lang = ?, default_priority = ?,
                budget_monthly = ?, jira_project_id = ?, test_owner_id = ?, dev_owner_id = ?,
                script_storage_path = ?, recording_storage_path = ?, storage_mode = ?,
                kb_enabled = ?, notify_feishu = ?, pool_quota_pct = ?,
                ai_page_inspect_enabled = ?,
                updated_at = now() WHERE id = ?
                """, name, input.defaultLang() == null || input.defaultLang().isBlank()
                        ? "zh-CN" : input.defaultLang().trim(),
                trimToNull(input.defaultPriority()), input.budgetMonthly(),
                jiraKey, testOwner, devOwner, scriptPath, recordingPath,
                mode == null ? 1 : mode, kbEnabled == null ? 1 : kbEnabled,
                notifyFeishu == null ? 0 : notifyFeishu, poolQuotaPct,
                pageInspectEnabled == null ? 0 : pageInspectEnabled, projectId);
        return get(projectId);
    }

 
    private void assertUserExists(Long userId, String label) {
        if (userId == null) {
            return;
        }
        Integer alive = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_user WHERE id = ? AND deleted = 0", Integer.class, userId);
        if (alive == null || alive == 0) {
            throw new ServiceException(
                    "%s不存在（用户 %d 未找到或已删除），请在「编辑项目」的负责人下拉中重新选择在职成员",
                    BizErrorCode.VALIDATION_ERROR, label, userId);
        }
    }

 
    private void assertStoragePath(String path, String label) {
        if (path != null && path.length() > 300) {
            throw new ServiceException("%s不能超过 300 字符，请在「编辑项目」的「项目属性」区改成更短的路径",
                    BizErrorCode.VALIDATION_ERROR, label);
        }
    }

    
    @Audit(objectType = "project", action = "archive", objectId = "#projectId", after = "#result")
    public ProjectInfo archive(long projectId) {
        get(projectId);
        jdbcTemplate.update("UPDATE tp_project SET status = 2, updated_at = now() WHERE id = ?", projectId);
        caseEventPublisher.publish(new CaseEventMessage(
                CaseEventType.PROJECT_INVALIDATED.name(), null, projectId, null,
                "ARCHIVE", null, null, null, null, System.currentTimeMillis(), null));
        return get(projectId);
    }

    
    @Override
    public PageResult<ProjectInfo> listArchived(int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        int offset = (safePage - 1) * safeSize;
        Long total = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_project p WHERE p.status = 2", Long.class);
        List<ProjectInfo> list = jdbcTemplate.query(
                "SELECT " + SELECT_COLUMNS + " FROM tp_project p WHERE p.status = 2"
                        + " ORDER BY p.updated_at DESC, p.id DESC LIMIT ? OFFSET ?",
                this::mapRow, new Object[] {0L, safeSize, offset});
        return new PageResult<>(list, total != null ? total : 0L);
    }

    
    @Override
    @Audit(objectType = "project", action = "restore", objectId = "#projectId", after = "#result")
    public ProjectInfo restore(long projectId) {
        ProjectInfo info = get(projectId);
        if (info.status() != 2) {
            throw new ServiceException("项目未归档（不在「归档项目」列表），无需还原；如需还原请前往「归档项目」列表选择已归档项目",
                    BizErrorCode.NOT_FOUND);
        }
        jdbcTemplate.update("UPDATE tp_project SET status = 1, updated_at = now() WHERE id = ?", projectId);
        return get(projectId);
    }

    
    @Override
    @Transactional
    @Audit(objectType = "project", action = "purge", objectId = "#projectId", after = "")
    public void purge(long operatorId, long projectId, boolean force) {
        get(projectId);
        DeleteCheckResult check = deleteGuard.checkProject(projectId);
        if (!check.deletable() && !force) {
            throw new ServiceException(
                    "该项目下还有 %s，无法删除；如确认级联删除，请在「项目管理」列表点击「彻底删除」并在弹窗确认级联后重试",
                    BizErrorCode.RESOURCE_CONFLICT, DeleteGuard.joinMessages(check.refs()));
        }
        jdbcTemplate.update("DELETE FROM tp_plan_deviation WHERE plan_id IN (SELECT id FROM tp_exec_plan WHERE project_id = ?)", projectId);
        jdbcTemplate.update("DELETE FROM tp_plan_case WHERE plan_id IN (SELECT id FROM tp_exec_plan WHERE project_id = ?)", projectId);
        jdbcTemplate.update("DELETE FROM tp_exec_task WHERE batch_id IN (SELECT id FROM tp_exec_batch WHERE project_id = ?) OR project_id = ?", projectId, projectId);
        jdbcTemplate.update("DELETE FROM tp_batch_case WHERE batch_id IN (SELECT id FROM tp_exec_batch WHERE project_id = ?)", projectId);
        jdbcTemplate.update("DELETE FROM tp_batch_report WHERE batch_id IN (SELECT id FROM tp_exec_batch WHERE project_id = ?)", projectId);
        jdbcTemplate.update("DELETE FROM tp_batch_param_snapshot WHERE batch_id IN (SELECT id FROM tp_exec_batch WHERE project_id = ?)", projectId);
        jdbcTemplate.update("DELETE FROM tp_batch_runtime_change WHERE batch_id IN (SELECT id FROM tp_exec_batch WHERE project_id = ?)", projectId);
        jdbcTemplate.update("DELETE FROM tp_batch_template WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_exec_batch WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_exec_plan WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_ai_call_log WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_ai_task WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_ai_batch WHERE project_id = ?", projectId);
        casePurgeSupport.cleanByProject(projectId);
        jdbcTemplate.update("DELETE FROM tp_test_case WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_case_requirement WHERE requirement_id IN (SELECT id FROM tp_requirement WHERE project_id = ?)", projectId);
        jdbcTemplate.update("DELETE FROM tp_requirement WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_defect WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_regression_suite WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_test_report WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_export_task WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_data_factory_run WHERE factory_id IN (SELECT id FROM tp_data_factory WHERE project_id = ?)", projectId);
        jdbcTemplate.update("DELETE FROM tp_data_factory WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_data_pool_item WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_tag_dict WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_apitest_run WHERE request_id IN "
                + "(SELECT r.id FROM tp_apitest_request r JOIN tp_apitest_collection c ON c.id = r.collection_id WHERE c.project_id = ?)", projectId);
        // 6a-2b. Collection-run ledger (V248): tp_apitest_run_set holds a NO
        // ACTION FK to the collection; the L3 probe proved purging a project
        // that owns a run set fails with an FK violation (HTTP 500). Delete
        // order: child runs first (runs reference run_set), then run_set,
        // then requests/collections.
        jdbcTemplate.update("DELETE FROM tp_apitest_run_set WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_apitest_request WHERE collection_id IN "
                + "(SELECT id FROM tp_apitest_collection WHERE project_id = ?)", projectId);
        // 6a-2c. Flow templates : project FK NO ACTION — purge of a
        // project owning a template dies on the FK without this row. The
        // provenance FK to the collection is ON DELETE SET NULL, so the
        // collection deletes above never block; templates go with the project.
        jdbcTemplate.update("DELETE FROM tp_apitest_flow_template WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_apitest_collection WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_apitest_global_var WHERE project_id = ?", projectId);
        // 6a-3b. Contract family ( closing journey proved the purge missed it:
        // purging a project that owns contract rows dies on
        // tp_api_spec_preview_project_id_fkey NO ACTION, while tp_api_spec and
        // tp_api_spec_change_analysis carry no FK at all and would linger as
        // orphans forever). Delete order: preview + change-queue first (preview
        // references tp_api_spec), then the specs and the subscriptions.
        jdbcTemplate.update("DELETE FROM tp_api_spec_preview WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_api_spec_change_analysis WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_spec_subscription WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_api_spec WHERE project_id = ?", projectId);
        // 6a-3c. Step aliases (project FK NO ACTION, found by the same FK census).
        jdbcTemplate.update("DELETE FROM tp_step_alias WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_monitor_target WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_perf_metric WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_perf_sample WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_perf_run WHERE project_id = ?", projectId);
        // tp_perf_approval carries a scenario_id FK onto tp_perf_scenario (D8 link),
        // so the approval ledger must leave BEFORE the scenario rows (A-13 vision
        // fixture proved the reversed order dies on tp_perf_approval_scenario_id_fkey).
        jdbcTemplate.update("DELETE FROM tp_perf_approval WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_perf_scenario WHERE project_id = ?", projectId);
        // CSV data pool: the ledger row carries a NO ACTION project FK, so
        // a purge of a project owning datasets dies on tp_perf_dataset_project_id_fkey
        // (ProjectPurgeFkTest proved it). Delete after the scenario — the soft
        // scenario.dataset_id reference is gone with it; the ledger's stored CSV
        // object is swept by the same project-wide object purge the other
        // artifact scenes rely on (object keys are project-routed).
        jdbcTemplate.update("DELETE FROM tp_perf_dataset WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_perf_whitelist WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_appauto_step_suggestion WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_appauto_ai_sample WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_appauto_popup_rule WHERE project_id = ?", projectId);
        // Reservation + takeover lanes ( FK census: both carry NO ACTION FKs to
        // the project AND to tp_appauto_device, so they must leave before the
        // device records; a project owning either row would otherwise die on purge).
        jdbcTemplate.update("DELETE FROM tp_appauto_takeover_request WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_appauto_reservation WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_appauto_lease_run WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_appauto_device WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_appauto_app WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_appauto_element WHERE project_id = ?", projectId);
        // Record drafts + env profiles (same census: NO ACTION FK to the project)
        jdbcTemplate.update("DELETE FROM tp_appauto_record_draft WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_appauto_env_profile WHERE project_id = ?", projectId);
        // Login-state ledger (V502): tp_login_state holds NO ACTION FKs to both
        // tp_credential and the project, so the ledger row must leave BEFORE its
        // vault row — the L4 fuse window proved a purge of a project owning login
        // states dies on tp_login_state_credential_id_fkey (HTTP 500).
        jdbcTemplate.update("DELETE FROM tp_login_state WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_credential WHERE project_id = ?", projectId);
        // Page Element Library rows (WE batch V553): tp_web_element holds a NO
        // ACTION FK to the project, so purging a project that registered ledger
        // elements died on tp_web_element_project_id_fkey (HTTP 500 — the
        // heal-evidence E2E cleanup step proved it). Anchor statistics carry ON
        // DELETE CASCADE from the element row and leave with it; heal evidence
        // is anchored to exec tasks (cascade), not to elements, so the earlier
        // task deletes already removed it.
        jdbcTemplate.update("DELETE FROM tp_web_element WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_db_connection WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_project_env WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_project_skill WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_skill WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_tool WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_explore_step WHERE session_id IN "
                + "(SELECT id FROM tp_explore_session WHERE project_id = ?)", projectId);
        jdbcTemplate.update("DELETE FROM tp_explore_session WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_cost_budget WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_project_plugin WHERE project_id = ?", projectId);
        // 6d. Shared-asset + journey reference family (E2E proven: purge of a project
        // that published assets or pinned segments died on
        // tp_asset_reference_referrer_project_id_fkey — the census of NO ACTION FKs to
        // tp_project created by V358/V379/V380; order = pins by referrer, pins onto this
        // project's asset rows, the asset rows, journey run-segment expansion rows,
        // plan segments). Journey plans themselves belong to the program and stay.
        jdbcTemplate.update("DELETE FROM tp_asset_reference WHERE referrer_project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_asset_reference WHERE asset_row_id IN "
                + "(SELECT id FROM tp_asset WHERE project_id = ?)", projectId);
        jdbcTemplate.update("DELETE FROM tp_asset WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_journey_run_segment WHERE project_id = ? OR segment_id IN "
                + "(SELECT id FROM tp_journey_segment WHERE project_id = ?)", projectId, projectId);
        jdbcTemplate.update("DELETE FROM tp_journey_segment WHERE project_id = ?", projectId);
        // 6e. Program lens bridge (Y-wave V340): tp_program_project holds a NO ACTION
        // FK to the project, so purging a project attached to any program died on
        // tp_program_project_project_id_fkey (HTTP 500 — E2E golden journey window
        // proved it in the fixture cleanup, ProjectPurgeProgramCascadeTest locks the
        // corrected cascade). The purge detaches THIS project's links only: the
        // program lens itself — tp_program, member grants (tp_user_program), journeys
        // and their runs — belongs to the program, not to any single project, and
        // stays. That is symmetric with ProgramService.detachProject never touching
        // program rows and with the journey-plan rule in section 6d; sibling
        // projects keep their view of the shared program untouched.
        jdbcTemplate.update("DELETE FROM tp_program_project WHERE project_id = ?", projectId);
        // 6f. Chat ledger (AN batch residue settlement). tp_ai_chat_session and
        //     tp_ai_chat_draft carry the project anchor as a plain column and
        //     hold no foreign key onto tp_project, so nothing in the schema
        //     removes them with the project: the live slot database carried 47
        //     draft cards under 45 projects that no longer exist, never-applied
        //     script-repair proposals included. Terminal state deletes what it
        //     created — frames and messages first (child before parent), then
        //     every card (project anchor or own session), then the sessions.
        //     Statement list is single-sourced in ChatLedgerPurgeSupport.
        chatLedgerPurge.purgeByProject(projectId);
        jdbcTemplate.update("DELETE FROM tp_user_project WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
    }

    
    public boolean exists(long projectId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_project WHERE id = ?", Long.class, projectId);
        return count != null && count > 0;
    }

 
    @Override
    public List<Long> activeProjectIds() {
        return jdbcTemplate.queryForList(
                "SELECT id FROM tp_project WHERE status = 1 ORDER BY id", Long.class);
    }

    
    public int getMemberRole(long projectId, long userId) {
        return roleResolver.roleOf(projectId, userId);
    }

    private ProjectInfo mapRow(ResultSet rs, int rowNum) throws SQLException {
        BigDecimal budget = rs.getBigDecimal("budget_monthly");
        long createdByRaw = rs.getLong("created_by");
        Long createdBy = rs.wasNull() ? null : createdByRaw;
        long testOwnerRaw = rs.getLong("test_owner_id");
        Long testOwnerId = rs.wasNull() ? null : testOwnerRaw;
        long devOwnerRaw = rs.getLong("dev_owner_id");
        Long devOwnerId = rs.wasNull() ? null : devOwnerRaw;
        return new ProjectInfo(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("code"),
                rs.getString("default_lang"),
                rs.getString("default_priority"),
                rs.getInt("status"),
                budget,
                createdBy,
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class),
                rs.getInt("viewer_role"),
                rs.getString("jira_project_id"),
                testOwnerId,
                devOwnerId,
                rs.getString("script_storage_path"),
                rs.getString("recording_storage_path"),
                rs.getInt("storage_mode"),
                rs.getInt("kb_enabled"),
                rs.getInt("notify_feishu"),
                rs.getInt("classify_vision"),
                (Integer) rs.getObject("pool_quota_pct"),
                rs.getInt("ai_page_inspect_enabled"));
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
