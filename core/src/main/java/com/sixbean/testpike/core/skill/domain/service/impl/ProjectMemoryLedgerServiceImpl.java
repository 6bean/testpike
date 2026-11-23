package com.sixbean.testpike.core.skill.domain.service.impl;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.audit.api.dto.AuditLogInput;
import com.sixbean.testpike.core.audit.domain.service.AuditLogService;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.common.domain.component.PageQueryPolicy;
import com.sixbean.testpike.core.skill.api.dto.ProjectMemoryCreateInput;
import com.sixbean.testpike.core.skill.api.dto.ProjectMemoryInfo;
import com.sixbean.testpike.core.skill.domain.service.ProjectMemoryLedgerService;
import com.sixbean.testpike.core.skill.infrastructure.dataobject.ProjectMemoryRow;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Ledger over tp_ai_ctx_memory (AP batch core side; state
 * machine re-ruled by section 8 to four states).
 * <p>State machine = four-state ruling (re-confirmed 11-03, plan A): pending-confirmation
 * (inject_status 2, distilled rows — promotable only through the shared draft
 * door), active (1), disabled (0), rejected (3); recycle bin = deleted 1.
 * Every transition is a
 * conditional UPDATE guarded by project+deleted so a concurrent governance
 * action surfaces as 40401 instead of a silent overwrite; each committed
 * transition leaves one audit row with before/after snapshots.
 * <p>Injection effect is intentionally NOT duplicated here: the assembly read
 * point (SkillConsumptionService.getAssemblyItems memory branch) already
 * filters inject_status=1 AND deleted=0 — single source per R40.
 */
@Service
public class ProjectMemoryLedgerServiceImpl implements ProjectMemoryLedgerService {

    private static final String OBJECT_TYPE = "project_memory";

    private final NamedParameterJdbcTemplate named;
    private final AuditLogService auditLogService;

    public ProjectMemoryLedgerServiceImpl(NamedParameterJdbcTemplate named,
                                          AuditLogService auditLogService) {
        this.named = named;
        this.auditLogService = auditLogService;
    }

    /**
 * Row projection. draft_id resolves the newest still-open confirmation
 * card for this entry via the target anchor written by the distillation
 * channel (tp_ai_chat_draft.target_id, indexed by (target_id, status));
 * chat-origin cards keep target_id NULL and never anchor here — the
 * design boundary of the distill-only channel .
 */
    private static final String COLS = """
            SELECT id, project_id, content, source_ref::text AS source_ref,
                   inject_status, hit_count, created_at, updated_at,
                   (SELECT d.id FROM tp_ai_chat_draft d
                    WHERE d.object_type = 'ctx_memory' AND d.status = 1
                      AND d.target_id = tp_ai_ctx_memory.id
                    ORDER BY d.id DESC LIMIT 1) AS draft_id
            """;

    private static final BeanPropertyRowMapper<ProjectMemoryRow> ROW_MAPPER =
            new BeanPropertyRowMapper<>(ProjectMemoryRow.class);

    @Override
    public PageResult<ProjectMemoryInfo> list(Long projectId, String keyword,
                                              Integer injectStatus, int page, int size) {
        requireProject(projectId);
        int safePage = Math.max(page, 1);
        int safeSize = PageQueryPolicy.enforceSize(size);
        MapSqlParameterSource ps = new MapSqlParameterSource("projectId", projectId);
        StringBuilder where = new StringBuilder(
                "FROM tp_ai_ctx_memory WHERE project_id = :projectId AND deleted = 0");
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND content ILIKE :keyword");
            ps.addValue("keyword", "%" + keyword.trim() + "%");
        }
        if (injectStatus != null) {
            where.append(" AND inject_status = :injectStatus");
            ps.addValue("injectStatus", injectStatus);
        }
        long total = count(where.toString(), ps);
        List<ProjectMemoryInfo> rows = named.query(
                COLS + where + " ORDER BY id DESC LIMIT :limit OFFSET :offset",
                ps.addValue("limit", safeSize).addValue("offset", (long) (safePage - 1) * safeSize),
                ROW_MAPPER).stream().map(ProjectMemoryRow::toInfo).toList();
        return new PageResult<>(rows, total);
    }

    @Override
    @Transactional
    public ProjectMemoryInfo create(Long operatorId, ProjectMemoryCreateInput input) {
        if (input == null || input.projectId() == null) {
            throw new ServiceException("缺少项目参数：新建记忆必须携带 projectId，"
                    + "请在顶栏选中项目后进入项目详情「项目记忆」页签，点击「新建记忆」重新提交",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (input.content() == null || input.content().isBlank()) {
            throw new ServiceException("记忆内容不能为空：请在「新建项目记忆」弹窗的「记忆内容」输入框填写内容后保存",
                    BizErrorCode.VALIDATION_ERROR);
        }
        requireProject(input.projectId());
        Long id = named.queryForObject("""
                INSERT INTO tp_ai_ctx_memory (project_id, content, source_ref, inject_status)
                VALUES (:projectId, :content, CAST(:sourceRef AS JSONB), 1)
                RETURNING id
                """, new MapSqlParameterSource("projectId", input.projectId())
                .addValue("content", input.content().trim())
                .addValue("sourceRef", "[{\"type\":\"manual\"}]"), Long.class);
        auditLogService.record(new AuditLogInput(operatorId, input.projectId(), OBJECT_TYPE,
                String.valueOf(id), "create", null, Map.of("content", input.content().trim())));
        return load(id);
    }

    @Override
    @Transactional
    public ProjectMemoryInfo setInjectStatus(Long operatorId, Long projectId, long id, int injectStatus) {
        if (injectStatus != 0 && injectStatus != 1) {
            throw new ServiceException("注入状态只能是 1（启用）或 0（停用）：正确写法 injectStatus=1 或 0，"
                    + "也可在项目详情「项目记忆」页签用「启用」「停用」按钮切换",
                    BizErrorCode.VALIDATION_ERROR);
        }
        ProjectMemoryInfo before = viewable(projectId, id);
        if (before.injectStatus() == 2 || before.injectStatus() == 3) {
            // Ruling section 8 : a distilled row is gated by the shared
            // confirmation door — the ledger has no power to self-activate a
            // pending entry, and a rejected one has no enable semantic.
            throw new ServiceException("待确认或已驳回的记忆条目不在启用/停用范围："
                    + "请在项目详情「项目记忆」页签对「待确认」条目点「采纳」或「驳回」，"
                    + "已驳回条目请「删除」后可从「项目记忆回收站」还原",
                    BizErrorCode.VALIDATION_ERROR);
        }
        int updated = named.update("""
                UPDATE tp_ai_ctx_memory SET inject_status = :to, updated_at = now()
                WHERE id = :id AND project_id = :projectId AND deleted = 0 AND inject_status <> :to
                """, new MapSqlParameterSource("to", injectStatus)
                .addValue("id", id).addValue("projectId", projectId));
        if (updated == 0 && before.injectStatus() != injectStatus) {
            throw notFound();
        }
        if (updated > 0) {
            auditLogService.record(new AuditLogInput(operatorId, projectId, OBJECT_TYPE,
                    String.valueOf(id), injectStatus == 1 ? "enable" : "disable",
                    Map.of("injectStatus", before.injectStatus()), Map.of("injectStatus", injectStatus)));
        }
        return load(id);
    }

    /**
     * Reject exit of the four-state machine (re-confirmed ruling): flips a
     * distilled row awaiting confirmation (2) to rejected (3). The entry stays
     * visible in the ledger for traceability and is never injected (the single
     * assembly read point only takes 1). Repeating the action on an already
     * rejected row returns the current view without stacking a second audit
     * row; any other state is a loud refusal with zero writes.
     */
    @Override
    @Transactional
    public ProjectMemoryInfo reject(Long operatorId, Long projectId, long id) {
        ProjectMemoryInfo before = viewable(projectId, id);
        if (before.injectStatus() == 3) {
            return before;
        }
        if (before.injectStatus() != 2) {
            throw new ServiceException("只有「待确认」状态的记忆条目可以驳回："
                    + "已生效或已停用条目请在项目详情「项目记忆」页签改用「停用」或「删除」",
                    BizErrorCode.VALIDATION_ERROR);
        }
        int updated = named.update("""
                UPDATE tp_ai_ctx_memory SET inject_status = 3, updated_at = now()
                WHERE id = :id AND project_id = :projectId AND deleted = 0 AND inject_status = 2
                """, new MapSqlParameterSource("id", id).addValue("projectId", projectId));
        if (updated == 0) {
            throw notFound();
        }
        auditLogService.record(new AuditLogInput(operatorId, projectId, OBJECT_TYPE,
                String.valueOf(id), "reject", Map.of("injectStatus", before.injectStatus()),
                Map.of("injectStatus", 3)));
        return load(id);
    }

    @Override
    @Transactional
    public void delete(Long operatorId, Long projectId, long id) {
        ProjectMemoryInfo before = viewable(projectId, id);
        int updated = named.update("""
                UPDATE tp_ai_ctx_memory SET deleted = 1, updated_at = now()
                WHERE id = :id AND project_id = :projectId AND deleted = 0
                """, new MapSqlParameterSource("id", id).addValue("projectId", projectId));
        if (updated == 0) {
            throw notFound();
        }
        auditLogService.record(new AuditLogInput(operatorId, projectId, OBJECT_TYPE,
                String.valueOf(id), "delete", Map.of("content", before.content()),
                Map.of("deleted", true)));
    }

    @Override
    public PageResult<ProjectMemoryInfo> recycleBin(Long projectId, int page, int size) {
        requireProject(projectId);
        int safePage = Math.max(page, 1);
        int safeSize = PageQueryPolicy.enforceSize(size);
        MapSqlParameterSource ps = new MapSqlParameterSource("projectId", projectId);
        long total = count("FROM tp_ai_ctx_memory WHERE project_id = :projectId AND deleted = 1", ps);
        List<ProjectMemoryInfo> rows = named.query(
                COLS + "FROM tp_ai_ctx_memory WHERE project_id = :projectId AND deleted = 1 "
                        + "ORDER BY id DESC LIMIT :limit OFFSET :offset",
                ps.addValue("limit", safeSize).addValue("offset", (long) (safePage - 1) * safeSize),
                ROW_MAPPER).stream().map(ProjectMemoryRow::toInfo).toList();
        return new PageResult<>(rows, total);
    }

    @Override
    @Transactional
    public ProjectMemoryInfo restore(Long operatorId, Long projectId, long id) {
        int updated = named.update("""
                UPDATE tp_ai_ctx_memory SET deleted = 0, updated_at = now()
                WHERE id = :id AND project_id = :projectId AND deleted = 1
                """, new MapSqlParameterSource("id", id).addValue("projectId", projectId));
        if (updated == 0) {
            throw notFound();
        }
        ProjectMemoryInfo after = load(id);
        auditLogService.record(new AuditLogInput(operatorId, projectId, OBJECT_TYPE,
                String.valueOf(id), "restore", Map.of("deleted", true),
                Map.of("deleted", false, "injectStatus", after.injectStatus())));
        return after;
    }

    // ---- guards ----

    private ProjectMemoryInfo viewable(Long projectId, long id) {
        List<ProjectMemoryRow> rows = named.query(
                COLS + "FROM tp_ai_ctx_memory WHERE id = :id AND project_id = :projectId AND deleted = 0",
                new MapSqlParameterSource("id", id).addValue("projectId", projectId),
                ROW_MAPPER);
        if (rows.isEmpty()) {
            throw notFound();
        }
        return rows.get(0).toInfo();
    }

    private ProjectMemoryInfo load(long id) {
        return named.queryForObject(
                COLS + "FROM tp_ai_ctx_memory WHERE id = :id",
                new MapSqlParameterSource("id", id),
                ROW_MAPPER).toInfo();
    }

    private void requireProject(Long projectId) {
        if (projectId == null) {
            throw new ServiceException("缺少项目参数：请求未携带 projectId，"
                    + "请先在顶栏选择一个项目再操作（项目清单见「项目管理」页）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        List<Integer> exists = named.queryForList("SELECT 1 FROM tp_project WHERE id = :id",
                new MapSqlParameterSource("id", projectId), Integer.class);
        if (exists.isEmpty()) {
            throw new ServiceException("项目不存在（projectId=" + projectId + "）："
                    + "请在顶栏重新选择项目，或到「项目管理」页确认该项目未被删除后重试",
                    BizErrorCode.NOT_FOUND);
        }
    }

    private long count(String fromWhere, MapSqlParameterSource ps) {
        Long total = named.queryForObject("SELECT COUNT(*) " + fromWhere, ps, Long.class);
        return total != null ? total : 0L;
    }

    private ServiceException notFound() {
        return new ServiceException("项目记忆条目不存在或已被并发治理："
                + "请回到项目详情「项目记忆」页签刷新列表，确认条目仍在后再操作",
                BizErrorCode.NOT_FOUND);
    }
}
