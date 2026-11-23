package com.sixbean.testpike.core.tool.domain.service.impl;

import com.sixbean.testpike.common.approval.ApprovalStateMachine;
import com.sixbean.testpike.common.approval.ApprovalStatus;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.audit.api.dto.AuditLogInput;
import com.sixbean.testpike.core.audit.domain.service.AuditLogService;
import com.sixbean.testpike.core.tool.api.dto.ProjectToolBindingInput;
import com.sixbean.testpike.core.tool.api.dto.ProjectToolBindingItem;
import com.sixbean.testpike.core.tool.api.dto.ProjectToolItem;
import com.sixbean.testpike.core.tool.api.dto.ProjectToolView;
import com.sixbean.testpike.core.tool.domain.component.ResolvedProjectToolBinding;
import com.sixbean.testpike.core.tool.domain.service.ProjectToolService;
import com.sixbean.testpike.core.tool.infrastructure.dataobject.ProjectToolBindingRow;
import com.sixbean.testpike.core.project.domain.component.ProjectLockSupport;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;


@Service
public class ProjectToolServiceImpl implements ProjectToolService {

    private static final String OBJECT_TYPE = "project_tool";
    private static final String AUDIT_ACTION = "bind";
    private static final String ENTITY_LABEL = "工具";
    
    private static final String SELECT_BINDINGS = """
            SELECT pt.tool_id, pt.enabled,
                   pt.updated_at AS bound_at, t.name, t.type
            FROM tp_project_tool pt
            JOIN tp_tool t ON t.id = pt.tool_id
            """;
    
    private static final String SELECT_REVISION = """
            SELECT COALESCE(MAX(EXTRACT(EPOCH FROM updated_at) * 1000000)::bigint, 0)
            FROM tp_project_tool WHERE project_id = :projectId
            """;

    private final NamedParameterJdbcTemplate named;
    private final AuditLogService auditLogService;
    private final ProjectLockSupport projectLock;

    public ProjectToolServiceImpl(NamedParameterJdbcTemplate named,
                                  AuditLogService auditLogService,
                                  ProjectLockSupport projectLock) {
        this.named = named;
        this.auditLogService = auditLogService;
        this.projectLock = projectLock;
    }

    @Override
    @Transactional
    public ProjectToolView saveBindings(Long operatorId, Long projectId,
                                        ProjectToolBindingInput input) {
        projectLock.lockProjectOrThrow(projectId);
        long currentRevision = currentRevision(projectId);
        if (input.expectedVersion() != null && input.expectedVersion() != currentRevision) {
            throw new ServiceException("项目 " + ENTITY_LABEL + " 启用状态已被他人修改（当前版本 " + currentRevision + "）——"
                    + "请在「工具管理」刷新读取最新启用清单，重新勾选后提交",
                    BizErrorCode.VERSION_CONFLICT);
        }
        List<ProjectToolBindingItem> items = input.tools();
        if (items == null) {
            throw new ServiceException("tools 不能为空（全量替换语义：清空请传 []）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        List<ResolvedProjectToolBinding> resolved = resolveAndValidate(items, projectId);

        List<ProjectToolItem> before = queryItems(projectId);
        // Idempotent replay short-circuit (ProjectSkillServiceImpl same posture): a PUT
        // whose resolved set equals the stored set writes nothing — timestamps,
        // revision, and the audit trail stay untouched.
        if (sameSet(before, resolved)) {
            return buildView(projectId);
        }
        named.update("DELETE FROM tp_project_tool WHERE project_id = :projectId",
                new MapSqlParameterSource("projectId", projectId));
        for (ResolvedProjectToolBinding rb : resolved) {
            named.update("""
                    INSERT INTO tp_project_tool (project_id, tool_id, enabled)
                    VALUES (:projectId, :toolId, :enabled)
                    """, new MapSqlParameterSource()
                    .addValue("projectId", projectId)
                    .addValue("toolId", rb.toolId())
                    .addValue("enabled", rb.enabled()));
        }
        recordAudit(operatorId, projectId, before, resolved);
        return buildView(projectId);
    }

    @Override
    public ProjectToolView getBindings(Long projectId) {
        Integer n = named.queryForObject("SELECT count(*) FROM tp_project WHERE id = :id",
                new MapSqlParameterSource("id", projectId), Integer.class);
        if (n == null || n == 0) {
            throw new ServiceException("项目不存在（id=" + projectId + "）", BizErrorCode.NOT_FOUND);
        }
        return buildView(projectId);
    }

    // ------------------------------------------------------------------
    // ------------------------------------------------------------------

    /**
     * Set-equality between stored enablements and the resolved incoming set
     * (key = toolId + enabled; the tool binding carries no role/priority).
     */
    private static boolean sameSet(List<ProjectToolItem> current,
                                   List<ResolvedProjectToolBinding> resolved) {
        if (current.size() != resolved.size()) {
            return false;
        }
        Set<String> a = new HashSet<>();
        for (ProjectToolItem item : current) {
            a.add(item.toolId() + ":" + item.enabled());
        }
        Set<String> b = new HashSet<>();
        for (ResolvedProjectToolBinding rb : resolved) {
            b.add(rb.toolId() + ":" + rb.enabled());
        }
        return a.equals(b);
    }

    private long currentRevision(Long projectId) {
        Long rev = named.queryForObject(SELECT_REVISION,
                new MapSqlParameterSource("projectId", projectId), Long.class);
        return rev == null ? 0L : rev;
    }

    // ------------------------------------------------------------------
    // ------------------------------------------------------------------

    
    private List<ResolvedProjectToolBinding> resolveAndValidate(
            List<ProjectToolBindingItem> items, Long projectId) {
        Set<Long> seen = new HashSet<>();
        List<ResolvedProjectToolBinding> resolved = new ArrayList<>(items.size());
        for (ProjectToolBindingItem item : items) {
            Long toolId = item.toolId();
            if (toolId == null) {
                throw new ServiceException("启用的 " + ENTITY_LABEL + " id 不能为空",
                        BizErrorCode.NOT_FOUND);
            }
            if (!seen.add(toolId)) {
                throw new ServiceException("启用的 " + ENTITY_LABEL + " id " + toolId
                        + " 重复（同一工具只能启用一次）", BizErrorCode.VALIDATION_ERROR);
            }
            int enabled = resolveEnabled(item.enabled());
            resolved.add(new ResolvedProjectToolBinding(toolId, enabled));
        }
        if (!resolved.isEmpty()) {
            checkToolGuards(resolved, projectId);
        }
        return resolved;
    }

    private int resolveEnabled(Integer raw) {
        if (raw == null) {
            return 1;
        }
        if (raw != 0 && raw != 1) {
            throw new ServiceException("启停开关 " + raw + " 只能为 0（停用）或 1（启用）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return raw;
    }

    
    private void checkToolGuards(List<ResolvedProjectToolBinding> resolved, Long projectId) {
        List<Long> ids = resolved.stream().map(ResolvedProjectToolBinding::toolId).toList();
        MapSqlParameterSource params = new MapSqlParameterSource("ids", ids);
        List<Map<String, Object>> rows = named.queryForList("""
                SELECT id, project_id, name, status FROM tp_tool WHERE id IN (:ids)
                """, params);
        Map<Long, Map<String, Object>> byId = new HashMap<>();
        for (Map<String, Object> row : rows) {
            byId.put(((Number) row.get("id")).longValue(), row);
        }
        for (ResolvedProjectToolBinding rb : resolved) {
            Map<String, Object> tool = byId.get(rb.toolId());
            if (tool == null) {
                throw new ServiceException(ENTITY_LABEL + " 不存在（id=" + rb.toolId() + "）",
                        BizErrorCode.NOT_FOUND);
            }
            String name = (String) tool.get("name");
            Object owner = tool.get("project_id");
            if (owner != null && ((Number) owner).longValue() != projectId) {
                throw new ServiceException(ENTITY_LABEL + "「" + name + "」是其他项目的"
                        + "项目级 " + ENTITY_LABEL + "，只能启用于其所属项目（系统级 " + ENTITY_LABEL
                        + " 可全局启用）", BizErrorCode.VALIDATION_ERROR);
            }
            int status = ((Number) tool.get("status")).intValue();
            if (!ApprovalStateMachine.isPublished(status)) {
                throw new ServiceException(ENTITY_LABEL + "「" + name + "」未发布"
                        + "（当前 " + ApprovalStatus.labelOf(status) + "），"
                        + ENTITY_LABEL + " 须先通过审批（published）才能被项目启用",
                        BizErrorCode.VALIDATION_ERROR);
            }
        }
    }

    // ------------------------------------------------------------------
    // ------------------------------------------------------------------

    private List<ProjectToolItem> queryItems(Long projectId) {
        return named.query(SELECT_BINDINGS + " WHERE pt.project_id = :projectId"
                        + " ORDER BY pt.tool_id ASC",
                new MapSqlParameterSource("projectId", projectId),
                new BeanPropertyRowMapper<>(ProjectToolBindingRow.class))
                .stream().map(ProjectToolBindingRow::toItem).toList();
    }

    private ProjectToolView buildView(Long projectId) {
        return new ProjectToolView(queryItems(projectId), currentRevision(projectId));
    }

    
    private void recordAudit(Long operatorId, Long projectId, List<ProjectToolItem> before,
                             List<ResolvedProjectToolBinding> after) {
        Map<String, Object> beforeMap = new LinkedHashMap<>();
        beforeMap.put("count", before.size());
        beforeMap.put("toolIds", before.stream().map(ProjectToolItem::toolId).toList());
        Map<String, Object> afterMap = new LinkedHashMap<>();
        afterMap.put("count", after.size());
        List<Map<String, Object>> summary = new ArrayList<>(after.size());
        for (ResolvedProjectToolBinding rb : after) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("toolId", rb.toolId());
            one.put("enabled", rb.enabled());
            summary.add(one);
        }
        afterMap.put("bindings", summary);
        auditLogService.record(new AuditLogInput(operatorId, projectId, OBJECT_TYPE,
                String.valueOf(projectId), AUDIT_ACTION, beforeMap, afterMap));
    }
}
