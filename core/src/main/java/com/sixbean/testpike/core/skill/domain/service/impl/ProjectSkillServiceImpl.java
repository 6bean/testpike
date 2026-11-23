package com.sixbean.testpike.core.skill.domain.service.impl;

import com.sixbean.testpike.common.approval.ApprovalStateMachine;
import com.sixbean.testpike.common.approval.ApprovalStatus;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.audit.api.dto.AuditLogInput;
import com.sixbean.testpike.core.audit.domain.service.AuditLogService;
import com.sixbean.testpike.core.skill.api.dto.ProjectSkillBindingInput;
import com.sixbean.testpike.core.skill.api.dto.ProjectSkillBindingItem;
import com.sixbean.testpike.core.skill.api.dto.ProjectSkillItem;
import com.sixbean.testpike.core.skill.api.dto.ProjectSkillView;
import com.sixbean.testpike.core.skill.domain.component.ProjectSkillRole;
import com.sixbean.testpike.core.skill.domain.component.ResolvedProjectSkillBinding;
import com.sixbean.testpike.core.skill.domain.service.ProjectSkillService;
import com.sixbean.testpike.core.skill.infrastructure.dataobject.ProjectSkillBindingRow;
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
public class ProjectSkillServiceImpl implements ProjectSkillService {

    private static final String OBJECT_TYPE = "project_skill";
    private static final String AUDIT_ACTION = "bind";
    private static final String ENTITY_LABEL = "SKILL";
    
    private static final String SELECT_BINDINGS = """
            SELECT ps.skill_id, ps.role, ps.priority, ps.enabled,
                   ps.updated_at AS bound_at, s.name, s.type
            FROM tp_project_skill ps
            JOIN tp_skill s ON s.id = ps.skill_id AND s.deleted = 0
            """;
    
    private static final String SELECT_REVISION = """
            SELECT COALESCE(MAX(EXTRACT(EPOCH FROM updated_at) * 1000000)::bigint, 0)
            FROM tp_project_skill WHERE project_id = :projectId
            """;

    private final NamedParameterJdbcTemplate named;
    private final AuditLogService auditLogService;
    private final ProjectLockSupport projectLock;

    public ProjectSkillServiceImpl(NamedParameterJdbcTemplate named,
                                   AuditLogService auditLogService,
                                   ProjectLockSupport projectLock) {
        this.named = named;
        this.auditLogService = auditLogService;
        this.projectLock = projectLock;
    }

    @Override
    @Transactional
    public ProjectSkillView saveBindings(Long operatorId, Long projectId,
                                         ProjectSkillBindingInput input) {
        projectLock.lockProjectOrThrow(projectId);
        long currentRevision = currentRevision(projectId);
        if (input.expectedVersion() != null && input.expectedVersion() != currentRevision) {
            throw new ServiceException("项目 " + ENTITY_LABEL + " 绑定已被他人修改，"
                    + "请刷新本项目详情页「SKILL 配置」页签后重试（当前版本 " + currentRevision + "）",
                    BizErrorCode.VERSION_CONFLICT);
        }
        List<ProjectSkillBindingItem> items = input.skills();
        if (items == null) {
            throw new ServiceException("skills 不能为空——该保存为全量替换语义，"
                    + "清空请传 []（页面操作可在「SKILL 配置」页签逐行点「解绑」后保存）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        List<ResolvedProjectSkillBinding> resolved = resolveAndValidate(items, projectId);

        List<ProjectSkillItem> before = queryItems(projectId);
        // Idempotent replay short-circuit: a PUT whose resolved set equals the stored
        // set is a no-op — zero delete/insert, revision and bound-at timestamps stay
        // untouched, and no audit row is appended. (Without this, a bare "save" click
        // rewrites every row's updated_at, which the UI echoes as a changed binding
        // time — a fabricated modification fact — and burns the optimistic-lock revision.)
        if (sameSet(before, resolved)) {
            return buildView(projectId);
        }
        named.update("DELETE FROM tp_project_skill WHERE project_id = :projectId",
                new MapSqlParameterSource("projectId", projectId));
        for (ResolvedProjectSkillBinding rb : resolved) {
            named.update("""
                    INSERT INTO tp_project_skill (project_id, skill_id, role, priority, enabled)
                    VALUES (:projectId, :skillId, :role, :priority, :enabled)
                    """, new MapSqlParameterSource()
                    .addValue("projectId", projectId)
                    .addValue("skillId", rb.skillId())
                    .addValue("role", rb.role())
                    .addValue("priority", rb.priority())
                    .addValue("enabled", rb.enabled()));
        }
        recordAudit(operatorId, projectId, before, resolved);
        return buildView(projectId);
    }

    @Override
    public ProjectSkillView getBindings(Long projectId) {
        Integer n = named.queryForObject("SELECT count(*) FROM tp_project WHERE id = :id",
                new MapSqlParameterSource("id", projectId), Integer.class);
        if (n == null || n == 0) {
            throw new ServiceException("项目不存在（id=" + projectId + "）"
                    + "——请到「项目管理」确认该项目未被删除后重新进入", BizErrorCode.NOT_FOUND);
        }
        return buildView(projectId);
    }

    // ------------------------------------------------------------------
    // ------------------------------------------------------------------

    /**
     * Set-equality between the stored bindings and the resolved incoming set
     * (key = skillId + role + priority + enabled). Order-insensitive: the read
     * path sorts by priority while the payload order is the editor's, so both
     * sides go through the same canonical key set.
     */
    private static boolean sameSet(List<ProjectSkillItem> current,
                                   List<ResolvedProjectSkillBinding> resolved) {
        if (current.size() != resolved.size()) {
            return false;
        }
        Set<String> a = new HashSet<>();
        for (ProjectSkillItem item : current) {
            a.add(canonicalKey(item.skillId(), item.role(), item.priority(), item.enabled()));
        }
        Set<String> b = new HashSet<>();
        for (ResolvedProjectSkillBinding rb : resolved) {
            b.add(canonicalKey(rb.skillId(), rb.role(), rb.priority(), rb.enabled()));
        }
        return a.equals(b);
    }

    private static String canonicalKey(Long id, Integer role, Integer priority, Integer enabled) {
        return id + ":" + role + ":" + priority + ":" + enabled;
    }

    private long currentRevision(Long projectId) {
        Long rev = named.queryForObject(SELECT_REVISION,
                new MapSqlParameterSource("projectId", projectId), Long.class);
        return rev == null ? 0L : rev;
    }

    // ------------------------------------------------------------------
    // ------------------------------------------------------------------

    
    private List<ResolvedProjectSkillBinding> resolveAndValidate(
            List<ProjectSkillBindingItem> items, Long projectId) {
        Set<Long> seen = new HashSet<>();
        List<ResolvedProjectSkillBinding> resolved = new ArrayList<>(items.size());
        for (ProjectSkillBindingItem item : items) {
            Long skillId = item.skillId();
            if (skillId == null) {
                throw new ServiceException("绑定的 " + ENTITY_LABEL + " id 不能为空"
                                + "——请在「SKILL 配置」页签通过「搜索并添加已发布 SKILL」重新选择",
                        BizErrorCode.NOT_FOUND);
            }
            if (!seen.add(skillId)) {
                throw new ServiceException("绑定的 " + ENTITY_LABEL + " id " + skillId
                        + " 重复（同一 SKILL 只能绑定一次）"
                        + "——请在「SKILL 配置」列表点「解绑」去除重复行后重新保存",
                        BizErrorCode.VALIDATION_ERROR);
            }
            int role = resolveRole(item.role());
            int enabled = resolveEnabled(item.enabled());
            int priority = item.priority() == null ? 0 : item.priority();
            resolved.add(new ResolvedProjectSkillBinding(skillId, role, priority, enabled));
        }
        if (!resolved.isEmpty()) {
            checkSkillGuards(resolved, projectId);
        }
        return resolved;
    }

    private int resolveRole(Integer raw) {
        if (raw == null) {
            return ProjectSkillRole.DEFAULT.code();
        }
        ProjectSkillRole role = ProjectSkillRole.of(raw);
        if (role == null) {
            throw new ServiceException("绑定角色 " + raw + " 不是合法取值——请在「注入角色」列选择（可选："
                    + ProjectSkillRole.allLabels() + "）", BizErrorCode.VALIDATION_ERROR);
        }
        return role.code();
    }

    private int resolveEnabled(Integer raw) {
        if (raw == null) {
            return 1;
        }
        if (raw != 0 && raw != 1) {
            throw new ServiceException("启停开关 " + raw + " 只能为 0（停用）或 1（启用）"
                            + "——请在「SKILL 配置」列表的「启用」列切换该行开关",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return raw;
    }

    
    private void checkSkillGuards(List<ResolvedProjectSkillBinding> resolved, Long projectId) {
        List<Long> ids = resolved.stream().map(ResolvedProjectSkillBinding::skillId).toList();
        MapSqlParameterSource params = new MapSqlParameterSource("ids", ids);
        List<Map<String, Object>> rows = named.queryForList("""
                SELECT id, project_id, name, status FROM tp_skill WHERE id IN (:ids) AND deleted = 0
                """, params);
        Map<Long, Map<String, Object>> byId = new HashMap<>();
        for (Map<String, Object> row : rows) {
            byId.put(((Number) row.get("id")).longValue(), row);
        }
        for (ResolvedProjectSkillBinding rb : resolved) {
            Map<String, Object> skill = byId.get(rb.skillId());
            if (skill == null) {
                throw new ServiceException(ENTITY_LABEL + " 不存在（id=" + rb.skillId() + "）"
                                + "——该 SKILL 可能已被删除，请到「SKILL 管理」的「回收站」还原，"
                                + "或在「搜索并添加已发布 SKILL」中改选其它条目",
                        BizErrorCode.NOT_FOUND);
            }
            String name = (String) skill.get("name");
            Object owner = skill.get("project_id");
            if (owner != null && ((Number) owner).longValue() != projectId) {
                throw new ServiceException(ENTITY_LABEL + "「" + name + "」是其他项目的"
                        + "项目级 " + ENTITY_LABEL + "，只能绑定其所属项目（系统级 " + ENTITY_LABEL
                        + " 可全局绑定）——请切换到其所属项目后在该项目详情页「SKILL 配置」页签绑定，"
                        + "或改选系统级 SKILL", BizErrorCode.VALIDATION_ERROR);
            }
            int status = ((Number) skill.get("status")).intValue();
            if (!ApprovalStateMachine.isPublished(status)) {
                throw new ServiceException(ENTITY_LABEL + "「" + name + "」未发布"
                        + "（当前 " + ApprovalStatus.labelOf(status) + "），"
                        + ENTITY_LABEL + " 须先通过审批（published）才能被项目绑定"
                        + "——请到「SKILL 管理」对该行点「提交审批」，审批通过后再添加",
                        BizErrorCode.VALIDATION_ERROR);
            }
        }
    }

    // ------------------------------------------------------------------
    // ------------------------------------------------------------------

    private List<ProjectSkillItem> queryItems(Long projectId) {
        return named.query(SELECT_BINDINGS + " WHERE ps.project_id = :projectId"
                        + " ORDER BY ps.priority DESC, ps.skill_id ASC",
                new MapSqlParameterSource("projectId", projectId),
                new BeanPropertyRowMapper<>(ProjectSkillBindingRow.class))
                .stream().map(ProjectSkillBindingRow::toItem).toList();
    }

    private ProjectSkillView buildView(Long projectId) {
        return new ProjectSkillView(queryItems(projectId), currentRevision(projectId));
    }

    
    private void recordAudit(Long operatorId, Long projectId, List<ProjectSkillItem> before,
                             List<ResolvedProjectSkillBinding> after) {
        Map<String, Object> beforeMap = new LinkedHashMap<>();
        beforeMap.put("count", before.size());
        beforeMap.put("skillIds", before.stream().map(ProjectSkillItem::skillId).toList());
        Map<String, Object> afterMap = new LinkedHashMap<>();
        afterMap.put("count", after.size());
        List<Map<String, Object>> summary = new ArrayList<>(after.size());
        for (ResolvedProjectSkillBinding rb : after) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("skillId", rb.skillId());
            one.put("role", rb.role());
            one.put("priority", rb.priority());
            one.put("enabled", rb.enabled());
            summary.add(one);
        }
        afterMap.put("bindings", summary);
        auditLogService.record(new AuditLogInput(operatorId, projectId, OBJECT_TYPE,
                String.valueOf(projectId), AUDIT_ACTION, beforeMap, afterMap));
    }
}
