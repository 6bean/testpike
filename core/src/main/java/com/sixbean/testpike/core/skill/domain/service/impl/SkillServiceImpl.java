package com.sixbean.testpike.core.skill.domain.service.impl;

import com.sixbean.testpike.common.approval.ApprovalEvent;
import com.sixbean.testpike.common.approval.ApprovalStateMachine;
import com.sixbean.testpike.common.approval.ApprovalStatus;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.audit.api.dto.AuditLogInput;
import com.sixbean.testpike.core.audit.domain.service.AuditLogService;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.common.domain.component.PageQueryPolicy;
import com.sixbean.testpike.core.common.domain.component.SortColumnPolicy;
import com.sixbean.testpike.core.skill.api.dto.SkillCreateInput;
import com.sixbean.testpike.core.skill.api.dto.SkillInfo;
import com.sixbean.testpike.core.skill.api.dto.SkillQuery;
import com.sixbean.testpike.core.skill.api.dto.SkillRecycleInfo;
import com.sixbean.testpike.core.skill.api.dto.SkillUpdateInput;
import com.sixbean.testpike.core.skill.api.dto.SkillVersionDetail;
import com.sixbean.testpike.core.skill.api.dto.SkillVersionInfo;
import com.sixbean.testpike.core.skill.domain.component.SkillApprovalNotifier;
import com.sixbean.testpike.core.skill.domain.component.SkillType;
import com.sixbean.testpike.core.skill.domain.service.SkillService;
import com.sixbean.testpike.core.skill.infrastructure.dataobject.SkillRecycleRow;
import com.sixbean.testpike.core.skill.infrastructure.dataobject.SkillRow;
import com.sixbean.testpike.core.skill.infrastructure.dataobject.SkillVersionRow;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


@Service
public class SkillServiceImpl implements SkillService {

 
    static final int NAME_MAX = 100;
 
    static final int BODY_MAX_BYTES = 256 * 1024;
 
    static final int REASON_MAX = 500;
 
    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "id", "s.id",
            "name", "s.name",
            "type", "s.type",
            "status", "s.status",
            "current_version", "s.current_version",
            "created_at", "s.created_at",
            "updated_at", "s.updated_at");
 
    private static final int MAX_OFFSET = 100_000;
    private static final String ENTITY_LABEL = "SKILL";
    private static final String OBJECT_TYPE = "skill";
    
    private static final String SELECT_COLUMNS = """
            SELECT s.id, s.project_id, s.name, s.type, s.status, s.current_version, s.version,
                   s.body, s.frontmatter::text AS frontmatter_json, s.created_by, s.approved_by,
                   s.approved_at, s.reject_reason, s.created_at, s.updated_at, s.deleted
            FROM tp_skill s
            """;
    
    private static final String RECYCLE_SELECT = """
            SELECT s.id, s.project_id, s.name, s.type, s.status, s.current_version, s.version,
                   la.operator_id AS deleted_by,
                   COALESCE(NULLIF(u.display_name, ''), u.username) AS deleted_by_name,
                   la.created_at AS deleted_at
            FROM tp_skill s
            LEFT JOIN LATERAL (
                SELECT operator_id, created_at FROM tp_audit_log
                WHERE object_type = 'skill' AND object_id = s.id::text AND action = 'delete'
                ORDER BY id DESC LIMIT 1) la ON true
            LEFT JOIN tp_user u ON u.id = la.operator_id
            """;

    private final NamedParameterJdbcTemplate named;
    private final AuditLogService auditLogService;
    private final SkillApprovalNotifier skillApprovalNotifier;

    public SkillServiceImpl(NamedParameterJdbcTemplate named, AuditLogService auditLogService,
                            SkillApprovalNotifier skillApprovalNotifier) {
        this.named = named;
        this.auditLogService = auditLogService;
        this.skillApprovalNotifier = skillApprovalNotifier;
    }

    @Override
    @Transactional
    public SkillInfo create(Long operatorId, SkillCreateInput input) {
        if (input == null) {
            throw new ServiceException("SKILL 内容不能为空——请在「SKILL 管理」页点「新建 SKILL」，"
                    + "填写「名称」、选择「类型」并写入「正文」后保存", BizErrorCode.VALIDATION_ERROR);
        }
        String name = validateName(input.name());
        SkillType type = validateType(input.type());
        String body = validateBody(input.body());
        checkNameUnique(input.projectId(), name);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("projectId", input.projectId())
                .addValue("name", name)
                .addValue("type", type.code())
                .addValue("status", ApprovalStatus.DRAFT.code())
                .addValue("body", body)
                .addValue("frontmatter", blankToNull(input.frontmatterJson()))
                .addValue("operatorId", operatorId);
        Long id;
        try {
            id = named.queryForObject("""
                    INSERT INTO tp_skill (project_id, name, type, status, body, frontmatter, created_by)
                    VALUES (:projectId, :name, :type, :status, :body, :frontmatter::jsonb, :operatorId)
                    RETURNING id
                    """, params, Long.class);
        } catch (DuplicateKeyException e) {
            throw conflictName(name);
        }
        named.update("""
                INSERT INTO tp_skill_version (skill_id, version, body, frontmatter,
                    status_at_snapshot, reason, created_by)
                VALUES (:skillId, 1, :body, :frontmatter::jsonb, :status, '新建', :operatorId)
                """, new MapSqlParameterSource()
                .addValue("skillId", id)
                .addValue("body", body)
                .addValue("frontmatter", blankToNull(input.frontmatterJson()))
                .addValue("status", ApprovalStatus.DRAFT.code())
                .addValue("operatorId", operatorId));
        SkillInfo created = get(id);
        recordAudit(operatorId, created.projectId(), id, "create", null, created);
        if (Boolean.TRUE.equals(input.submitNow())) {
            return transition(operatorId, id, ApprovalEvent.SUBMIT, created.version(), null, "submit");
        }
        return created;
    }

    @Override
    public SkillInfo get(Long id) {
        List<SkillInfo> rows = queryInfo("WHERE s.id = :id AND s.deleted = 0",
                new MapSqlParameterSource("id", id));
        if (rows.isEmpty()) {
            throw new ServiceException("SKILL 不存在或在回收站（id=" + id + "）——请刷新「SKILL 管理」列表确认；"
                    + "若已被删除请打开「回收站」还原后再操作", BizErrorCode.NOT_FOUND);
        }
        return rows.get(0);
    }

    // ------------------------------------------------------------------
    // ------------------------------------------------------------------

    @Override
    public PageResult<SkillInfo> list(SkillQuery query) {
        int safePage = Math.max(query.page(), 1);
        int safeSize = PageQueryPolicy.enforceSize(query.size());
        long offset = PageQueryPolicy.clampOffset(safePage, safeSize, MAX_OFFSET);
        String sortColumn = SortColumnPolicy.require(query.sortBy(), SORT_COLUMNS, ENTITY_LABEL);
        if (sortColumn == null) {
            sortColumn = "s.created_at";
        }
        String sortDir = "asc".equalsIgnoreCase(query.sortDir()) ? "ASC" : "DESC";

        MapSqlParameterSource params = new MapSqlParameterSource();
        StringBuilder where = new StringBuilder(" WHERE s.deleted = 0");
        if (query.keyword() != null && !query.keyword().isBlank()) {
            where.append(" AND (s.name ILIKE :keyword OR COALESCE(s.body, '') ILIKE :keyword)");
            params.addValue("keyword", "%" + query.keyword().trim() + "%");
        }
        if (query.type() != null) {
            where.append(" AND s.type = :type");
            params.addValue("type", query.type());
        }
        if (query.status() != null) {
            where.append(" AND s.status = :status");
            params.addValue("status", query.status());
        }
        if (query.projectId() != null) {
            // AR batch: project segment = own bucket ∪ system level (project_id IS
            // NULL). Other projects' private rows still never appear (H1).
            where.append(" AND (s.project_id = :projectId OR s.project_id IS NULL)");
            params.addValue("projectId", query.projectId());
        }
        if (query.tagId() != null) {
            where.append(" AND EXISTS (SELECT 1 FROM tp_skill_tag st"
                    + " WHERE st.skill_id = s.id AND st.tag_id = :tagId)");
            params.addValue("tagId", query.tagId());
        }
        Long total = named.queryForObject(
                "SELECT count(*) FROM tp_skill s" + where, params, Long.class);
        params.addValue("size", safeSize).addValue("offset", offset);
        List<SkillInfo> list = queryInfo(where + " ORDER BY " + sortColumn + " " + sortDir
                + ", s.id DESC LIMIT :size OFFSET :offset", params);
        return new PageResult<>(list, total != null ? total : 0L);
    }

    // ------------------------------------------------------------------
    // ------------------------------------------------------------------

    @Override
    @Transactional
    public SkillInfo update(Long operatorId, Long id, SkillUpdateInput input) {
        if (input == null) {
            throw new ServiceException("SKILL 编辑内容不能为空——请在「编辑 SKILL」弹窗填写「正文」等字段后保存",
                    BizErrorCode.VALIDATION_ERROR);
        }
        SkillRow current = queryRow(id);
        if (current.getStatus() == ApprovalStatus.RETIRED.code()) {
            throw new ServiceException("SKILL 已终止（" + ApprovalStatus.labelOf(current.getStatus())
                    + "），不能编辑；如需复用请到「SKILL 管理」点「新建 SKILL」另建一份",
                    BizErrorCode.VALIDATION_ERROR);
        }
        String name = input.name() == null ? current.getName() : validateName(input.name());
        Integer typeCode = input.type() == null ? current.getType() : validateType(input.type()).code();
        String body = input.body() == null ? current.getBody() : validateBody(input.body());
        String frontmatter = input.frontmatterJson() == null
                ? current.getFrontmatterJson() : blankToNull(input.frontmatterJson());
        if (!name.equals(current.getName())) {
            checkNameUniqueExcept(current.getProjectId(), name, id);
        }
        boolean contentChanged = input.body() != null && !body.equals(current.getBody())
                || input.frontmatterJson() != null && !java.util.Objects.equals(
                        frontmatter, current.getFrontmatterJson());
        boolean published = ApprovalStateMachine.isPublished(current.getStatus());

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("expected", input.expectedVersion())
                .addValue("name", name)
                .addValue("type", typeCode)
                .addValue("body", body)
                .addValue("frontmatter", frontmatter)
                .addValue("operatorId", operatorId);
        Integer newCurrentVersion;
        if (published && contentChanged) {
            ApprovalStateMachine.transition(current.getStatus(), ApprovalEvent.SUBMIT, ENTITY_LABEL);
            List<Integer> bumped = named.queryForList("""
                    UPDATE tp_skill SET name = :name, type = :type, body = :body,
                        frontmatter = :frontmatter::jsonb,
                        status = :pending, current_version = current_version + 1,
                        version = version + 1, reject_reason = NULL, updated_at = now()
                    WHERE id = :id AND version = :expected
                    RETURNING current_version
                    """, params.addValue("pending", ApprovalStatus.PENDING_APPROVAL.code()),
                    Integer.class);
            if (bumped.isEmpty()) {
                throw versionConflict(current.getVersion());
            }
            newCurrentVersion = bumped.get(0);
            named.update("""
                    INSERT INTO tp_skill_version (skill_id, version, body, frontmatter,
                        status_at_snapshot, reason, created_by)
                    VALUES (:id, :newVersion, :body, :frontmatter::jsonb, :pending, '改版重审', :operatorId)
                    """, params.addValue("newVersion", newCurrentVersion));
        } else {
            newCurrentVersion = current.getCurrentVersion();
            if (published) {
                if (named.update("""
                        UPDATE tp_skill SET name = :name, type = :type,
                            version = version + 1, updated_at = now()
                        WHERE id = :id AND version = :expected
                        """, params) == 0) {
                    throw versionConflict(current.getVersion());
                }
            } else {
                int touched = named.update("""
                        UPDATE tp_skill SET name = :name, type = :type, body = :body,
                            frontmatter = :frontmatter::jsonb,
                            version = version + 1, updated_at = now()
                        WHERE id = :id AND version = :expected
                        """, params);
                if (touched == 0) {
                    throw versionConflict(current.getVersion());
                }
                if (contentChanged) {
                    named.update("""
                            UPDATE tp_skill_version SET body = :body, frontmatter = :frontmatter::jsonb
                            WHERE skill_id = :id AND version = :currentVersion
                            """, params.addValue("currentVersion", newCurrentVersion));
                }
            }
        }
        SkillInfo after = get(id);
        recordAudit(operatorId, after.projectId(), id, "update",
                Map.of("status", current.getStatus(),
                        "statusLabel", ApprovalStatus.labelOf(current.getStatus()),
                        "name", current.getName(), "currentVersion", current.getCurrentVersion()),
                after);
        if (published && contentChanged) {
            skillApprovalNotifier.notifySubmitted(operatorId, after, true);
        }
        return after;
    }

    // ------------------------------------------------------------------
    // ------------------------------------------------------------------

    @Override
    public List<SkillVersionInfo> listVersions(Long id) {
        get(id);
        return named.query("""
                SELECT skill_id, version, body, frontmatter::text AS frontmatter_json,
                       status_at_snapshot, reason, approved_at, created_by, created_at
                FROM tp_skill_version WHERE skill_id = :id ORDER BY version DESC
                """, new MapSqlParameterSource("id", id),
                new BeanPropertyRowMapper<>(SkillVersionRow.class))
                .stream().map(SkillVersionRow::toInfo).toList();
    }

    @Override
    public SkillVersionDetail getVersion(Long id, int version) {
        get(id);
        List<SkillVersionDetail> rows = named.query("""
                SELECT skill_id, version, body, frontmatter::text AS frontmatter_json,
                       status_at_snapshot, reason, approved_at, created_by, created_at
                FROM tp_skill_version WHERE skill_id = :id AND version = :version
                """, new MapSqlParameterSource("id", id).addValue("version", version),
                new BeanPropertyRowMapper<>(SkillVersionRow.class))
                .stream().map(SkillVersionRow::toDetail).toList();
        if (rows.isEmpty()) {
            throw new ServiceException("SKILL 版本快照不存在（id=" + id + ", v" + version
                    + "）——请在「版本历史」抽屉重新选择要查看的版本",
                    BizErrorCode.NOT_FOUND);
        }
        return rows.get(0);
    }

    // ------------------------------------------------------------------
    // ------------------------------------------------------------------

    @Override
    @Transactional
    public void delete(Long operatorId, Long id, int expectedVersion) {
        SkillRow current = queryRow(id);
        if (ApprovalStateMachine.isPublished(current.getStatus())) {
            List<String> boundProjects = named.queryForList("""
                    SELECT p.name FROM tp_project_skill ps
                    JOIN tp_project p ON p.id = ps.project_id
                    WHERE ps.skill_id = :id ORDER BY p.name
                    """, new MapSqlParameterSource("id", current.getId()), String.class);
            if (!boundProjects.isEmpty()) {
                throw new ServiceException("SKILL「" + current.getName() + "」已被 "
                        + boundProjects.size() + " 个项目绑定（" + String.join("、", boundProjects)
                        + "），不能删除；请到各项目的详情页「SKILL 配置」页签对该行点「解绑」并保存后再删除",
                        BizErrorCode.RESOURCE_CONFLICT);
            }
            throw new ServiceException("已发布的 SKILL「" + current.getName()
                    + "」不能直接删除——请先在「SKILL 管理」列表对该行点「下架」，"
                    + "或前往「删除审批」页提交删除申请",
                    BizErrorCode.VALIDATION_ERROR);
        }
        int deleted = named.update("""
                UPDATE tp_skill SET deleted = 1, updated_at = now()
                WHERE id = :id AND version = :expected AND deleted = 0
                """, new MapSqlParameterSource("id", id).addValue("expected", expectedVersion));
        if (deleted == 0) {
            throw versionConflict(current.getVersion());
        }
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("id", current.getId());
        before.put("name", current.getName());
        before.put("status", current.getStatus());
        before.put("statusLabel", ApprovalStatus.labelOf(current.getStatus()));
        before.put("currentVersion", current.getCurrentVersion());
        auditLogService.record(new AuditLogInput(operatorId, current.getProjectId(), OBJECT_TYPE,
                String.valueOf(id), "delete", before, Map.of("deleted", true, "recycled", true)));
    }

    // ------------------------------------------------------------------
    // ------------------------------------------------------------------

    @Override
    public PageResult<SkillRecycleInfo> recycleBin(Long projectId, String keyword, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = PageQueryPolicy.enforceSize(size);
        long offset = PageQueryPolicy.clampOffset(safePage, safeSize, MAX_OFFSET);
        MapSqlParameterSource params = new MapSqlParameterSource();
        StringBuilder where = new StringBuilder(" WHERE s.deleted = 1");
        if (projectId != null) {
            where.append(" AND s.project_id = :projectId");
            params.addValue("projectId", projectId);
        }
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND s.name ILIKE :keyword");
            params.addValue("keyword", "%" + keyword.trim() + "%");
        }
        Long total = named.queryForObject("SELECT count(*) FROM tp_skill s" + where,
                params, Long.class);
        params.addValue("size", safeSize).addValue("offset", offset);
        List<SkillRecycleInfo> list = named.query(RECYCLE_SELECT + where
                        + " ORDER BY la.created_at DESC NULLS LAST, s.id DESC LIMIT :size OFFSET :offset",
                params, new BeanPropertyRowMapper<>(SkillRecycleRow.class))
                .stream().map(SkillRecycleRow::toInfo).toList();
        return new PageResult<>(list, total != null ? total : 0L);
    }

    @Override
    public SkillRecycleInfo recycleEntry(Long id) {
        List<SkillRecycleInfo> rows = named.query(RECYCLE_SELECT + " WHERE s.id = :id AND s.deleted = 1",
                new MapSqlParameterSource("id", id), new BeanPropertyRowMapper<>(SkillRecycleRow.class))
                .stream().map(SkillRecycleRow::toInfo).toList();
        if (rows.isEmpty()) {
            throw new ServiceException("SKILL 不存在或不在回收站（活跃 SKILL 请直接查看）"
                            + "——请在「SKILL 管理」列表查看活跃 SKILL，或刷新「回收站」确认该行是否已还原",
                    BizErrorCode.NOT_FOUND);
        }
        return rows.get(0);
    }

    @Override
    @Transactional
    public SkillInfo restore(Long operatorId, Long id, int expectedVersion) {
        SkillRecycleInfo entry = recycleEntry(id);
        if (entry.version() != expectedVersion) {
            throw new ServiceException("SKILL 版本已变更（当前版本 " + entry.version()
                    + "），请刷新「SKILL 回收站」列表后重新发起还原", BizErrorCode.VERSION_CONFLICT);
        }
        int updated = named.update("""
                UPDATE tp_skill SET deleted = 0, updated_at = now()
                WHERE id = :id AND version = :expected AND deleted = 1
                """, new MapSqlParameterSource("id", id).addValue("expected", expectedVersion));
        if (updated == 0) {
            throw new ServiceException("SKILL 已被他人修改（版本冲突），"
                    + "请刷新「SKILL 回收站」列表后重新发起还原",
                    BizErrorCode.VERSION_CONFLICT);
        }
        auditLogService.record(new AuditLogInput(operatorId, entry.projectId(), OBJECT_TYPE,
                String.valueOf(id), "restore", Map.of("deleted", true, "name", entry.name()),
                Map.of("deleted", false, "restored", true)));
        return get(id);
    }


    @Override
    @Transactional
    public SkillInfo submit(Long operatorId, Long id, int expectedVersion) {
        return transition(operatorId, id, ApprovalEvent.SUBMIT, expectedVersion, null, "submit");
    }

    @Override
    @Transactional
    public SkillInfo approve(Long operatorId, Long id, int expectedVersion) {
        SkillInfo current = get(id);
        if (ApprovalStateMachine.isPublished(current.status())) {
            return current;
        }
        return transition(operatorId, id, ApprovalEvent.APPROVE, expectedVersion, null, "approve");
    }

    @Override
    @Transactional
    public SkillInfo reject(Long operatorId, Long id, int expectedVersion, String reason) {
        if (reason == null || reason.trim().isEmpty()) {
            throw new ServiceException("拒绝必须填写原因——请在「审批拒绝」弹窗的输入框写清拒绝理由"
                    + "后点「确认拒绝」", BizErrorCode.VALIDATION_ERROR);
        }
        String trimmed = reason.trim();
        if (trimmed.length() > REASON_MAX) {
            throw new ServiceException("拒绝原因最长 " + REASON_MAX + " 字（当前 " + trimmed.length()
                    + " 字）——请精简「拒绝原因」后重新提交", BizErrorCode.VALIDATION_ERROR);
        }
        return transition(operatorId, id, ApprovalEvent.REJECT, expectedVersion, trimmed, "reject");
    }

    @Override
    @Transactional
    public SkillInfo retire(Long operatorId, Long id, int expectedVersion) {
        return transition(operatorId, id, ApprovalEvent.RETIRE, expectedVersion, null, "retire");
    }

    // ------------------------------------------------------------------
    // ------------------------------------------------------------------

    private SkillInfo transition(Long operatorId, Long id, ApprovalEvent event,
                                 int expectedVersion, String rejectReason, String auditAction) {
        SkillInfo current = get(id);
        int target = ApprovalStateMachine.transition(current.status(), event, ENTITY_LABEL);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("target", target)
                .addValue("id", id)
                .addValue("expected", expectedVersion);
        StringBuilder sql = new StringBuilder("""
                UPDATE tp_skill SET status = :target, version = version + 1, updated_at = now()
                """);
        if (target == ApprovalStatus.PUBLISHED.code()) {
            sql.append(", approved_by = :operatorId, approved_at = now(), reject_reason = NULL");
            params.addValue("operatorId", operatorId);
        } else if (target == ApprovalStatus.REJECTED.code()) {
            sql.append(", reject_reason = :reason");
            params.addValue("reason", rejectReason);
        } else if (target == ApprovalStatus.PENDING_APPROVAL.code()) {
            sql.append(", reject_reason = NULL");
        }
        sql.append(" WHERE id = :id AND version = :expected");
        if (named.update(sql.toString(), params) == 0) {
            throw new ServiceException("SKILL 已被他人修改（当前版本 "
                    + current.version() + "）——请刷新「SKILL 管理」列表取最新版本后重试",
                    BizErrorCode.VERSION_CONFLICT);
        }
        if (target == ApprovalStatus.PUBLISHED.code()) {
            named.update("""
                    UPDATE tp_skill_version SET approved_at = now()
                    WHERE skill_id = :id
                      AND version = (SELECT current_version FROM tp_skill WHERE id = :id)
                    """, new MapSqlParameterSource("id", id));
        }
        SkillInfo after = get(id);
        recordAudit(operatorId, after.projectId(), id, auditAction,
                Map.of("status", current.status(), "statusLabel", current.statusLabel()), after);
        if (event == ApprovalEvent.SUBMIT) {
            skillApprovalNotifier.notifySubmitted(operatorId, after, false);
        } else if (target == ApprovalStatus.PUBLISHED.code()) {
            skillApprovalNotifier.notifyApproved(operatorId, after);
        } else if (target == ApprovalStatus.REJECTED.code()) {
            skillApprovalNotifier.notifyRejected(operatorId, after);
        }
        return after;
    }

    // ------------------------------------------------------------------
    // ------------------------------------------------------------------

    private String validateName(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            throw new ServiceException("SKILL 名称不能为空——请在「名称」输入框填写名称"
                    + "（示例：case-step-granularity-check）", BizErrorCode.VALIDATION_ERROR);
        }
        String name = raw.trim();
        if (name.length() > NAME_MAX) {
            throw new ServiceException("SKILL 名称最长 " + NAME_MAX + " 字（当前 "
                    + name.length() + " 字）——请精简「名称」后重新保存",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return name;
    }

    private SkillType validateType(Integer raw) {
        if (raw == null) {
            throw new ServiceException("SKILL 类型不能为空——请在「类型」下拉选择（可选："
                    + SkillType.allLabels() + "）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        SkillType type = SkillType.of(raw);
        if (type == null) {
            throw new ServiceException("SKILL 类型 " + raw + " 不是合法取值——请在「类型」下拉选择（可选："
                    + SkillType.allLabels() + "）", BizErrorCode.VALIDATION_ERROR);
        }
        return type;
    }

    private String validateBody(String body) {
        if (body == null || body.trim().isEmpty()) {
            throw new ServiceException("SKILL 正文（body）不能为空——请在「正文」编辑器写入 Markdown 内容后保存",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > BODY_MAX_BYTES) {
            throw new ServiceException("SKILL 正文超过 256KB 上限——请按主题拆分「正文」或精简内容后重新保存",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return body;
    }

    private void checkNameUnique(Long projectId, String name) {
        checkNameUniqueExcept(projectId, name, null);
    }

    
    private void checkNameUniqueExcept(Long projectId, String name, Long excludeId) {
        String where = projectId == null ? "project_id IS NULL" : "project_id = :projectId";
        MapSqlParameterSource params = new MapSqlParameterSource("name", name);
        if (projectId != null) {
            params.addValue("projectId", projectId);
        }
        if (excludeId != null) {
            where += " AND id <> :excludeId";
            params.addValue("excludeId", excludeId);
        }
        Integer n = named.queryForObject(
                "SELECT count(*) FROM tp_skill WHERE " + where + " AND name = :name",
                params, Integer.class);
        if (n != null && n > 0) {
            throw conflictName(name);
        }
    }

    private ServiceException conflictName(String name) {
        return new ServiceException("SKILL 名称「" + name + "」已存在（同一项目/系统级内不允许重名）"
                + "——请改用其它名称，或在「SKILL 管理」搜索该名称后点「编辑」修改原文",
                BizErrorCode.RESOURCE_CONFLICT);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    // ------------------------------------------------------------------
    // ------------------------------------------------------------------

    
    private SkillRow queryRow(Long id) {
        List<SkillRow> rows = named.query(SELECT_COLUMNS + " WHERE s.id = :id AND s.deleted = 0",
                new MapSqlParameterSource("id", id), new BeanPropertyRowMapper<>(SkillRow.class));
        if (rows.isEmpty()) {
            throw new ServiceException("SKILL 不存在或在回收站（id=" + id + "）——请刷新「SKILL 管理」列表确认；"
                    + "若已被删除请打开「回收站」还原后再操作", BizErrorCode.NOT_FOUND);
        }
        return rows.get(0);
    }

    private ServiceException versionConflict(int currentVersion) {
        return new ServiceException("SKILL 已被他人修改（当前版本 "
                + currentVersion + "）——请刷新「SKILL 管理」列表取最新版本后重试",
                BizErrorCode.VERSION_CONFLICT);
    }

    
    private List<SkillInfo> queryInfo(String whereClause, MapSqlParameterSource params) {
        return named.query(SELECT_COLUMNS + " " + whereClause, params,
                        new BeanPropertyRowMapper<>(SkillRow.class))
                .stream().map(SkillRow::toInfo).toList();
    }

    
    private void recordAudit(Long operatorId, Long projectId, Long skillId, String action,
                             Map<String, Object> before, SkillInfo after) {
        Map<String, Object> afterMap = new LinkedHashMap<>();
        afterMap.put("id", after.id());
        afterMap.put("name", after.name());
        afterMap.put("type", after.type());
        afterMap.put("status", after.status());
        afterMap.put("statusLabel", after.statusLabel());
        afterMap.put("version", after.version());
        afterMap.put("currentVersion", after.currentVersion());
        if (after.rejectReason() != null) {
            afterMap.put("rejectReason", after.rejectReason());
        }
        auditLogService.record(new AuditLogInput(operatorId, projectId, OBJECT_TYPE,
                String.valueOf(skillId), action, before, afterMap));
    }
}
