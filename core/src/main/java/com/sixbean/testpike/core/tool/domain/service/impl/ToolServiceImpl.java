package com.sixbean.testpike.core.tool.domain.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
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
import com.sixbean.testpike.core.tool.api.dto.ToolCreateInput;
import com.sixbean.testpike.core.tool.api.dto.ToolDetail;
import com.sixbean.testpike.core.tool.api.dto.ToolDownloadFile;
import com.sixbean.testpike.core.tool.api.dto.ToolInfo;
import com.sixbean.testpike.core.tool.api.dto.ToolQuery;
import com.sixbean.testpike.core.tool.api.dto.ToolRollbackInput;
import com.sixbean.testpike.core.tool.api.dto.ToolUpdateInput;
import com.sixbean.testpike.core.tool.api.dto.ToolVersionDetail;
import com.sixbean.testpike.core.tool.api.dto.ToolVersionInfo;
import com.sixbean.testpike.core.tool.domain.component.ToolApprovalNotifier;
import com.sixbean.testpike.core.tool.domain.component.ToolExecKind;
import com.sixbean.testpike.core.tool.domain.component.ToolPackSupport;
import com.sixbean.testpike.core.tool.domain.component.ToolSchemaValidator;
import com.sixbean.testpike.core.tool.domain.component.ToolType;
import com.sixbean.testpike.core.tool.domain.service.ToolService;
import com.sixbean.testpike.core.tool.infrastructure.dataobject.ToolRow;
import com.sixbean.testpike.core.tool.infrastructure.dataobject.ToolVersionRow;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;


@Service
public class ToolServiceImpl implements ToolService {

    
    static final int NAME_MAX = 100;
    
    static final int TIMEOUT_DEFAULT_MS = 30_000;
    
    static final int TIMEOUT_MAX_MS = 600_000;
    
    static final int REASON_MAX = 500;
    
    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "id", "t.id",
            "name", "t.name",
            "type", "t.type",
            "category", "t.category",
            "status", "t.status",
            "current_version", "t.current_version",
            "timeout_ms", "t.timeout_ms",
            "created_at", "t.created_at",
            "updated_at", "t.updated_at");
    
    private static final int MAX_OFFSET = 100_000;
    private static final String ENTITY_LABEL = "工具";
    private static final String OBJECT_TYPE = "tool";
    
    private static final String SELECT_COLUMNS = """
            SELECT t.id, t.project_id, t.name, t.type, t.category, t.description, t.body,
                   t.tags::text AS tags_json, t.input_schema::text AS input_schema_json,
                   t.output_schema::text AS output_schema_json, t.exec_kind,
                   t.exec_spec::text AS exec_spec_json, t.timeout_ms, t.status,
                   t.current_version, t.version, t.created_by, t.approved_by,
                   t.approved_at, t.reject_reason, t.created_at, t.updated_at
            FROM tp_tool t
            """;
    
    private static final String SELECT_VERSION_COLUMNS = """
            SELECT tool_id, version, body, input_schema::text AS input_schema_json,
                   output_schema::text AS output_schema_json, exec_spec::text AS exec_spec_json,
                   timeout_ms, status_at_snapshot, reason, created_by, created_at
            FROM tp_tool_version WHERE tool_id = :id
            """;

    private final NamedParameterJdbcTemplate named;
    private final AuditLogService auditLogService;
    private final ToolApprovalNotifier toolApprovalNotifier;
    private final ToolSchemaValidator schemaValidator;
    private final ToolPackSupport toolPackSupport;

    public ToolServiceImpl(NamedParameterJdbcTemplate named, AuditLogService auditLogService,
                           ToolApprovalNotifier toolApprovalNotifier,
                           ToolSchemaValidator schemaValidator,
                           ToolPackSupport toolPackSupport) {
        this.named = named;
        this.auditLogService = auditLogService;
        this.toolApprovalNotifier = toolApprovalNotifier;
        this.schemaValidator = schemaValidator;
        this.toolPackSupport = toolPackSupport;
    }

    // ------------------------------------------------------------------
    // ------------------------------------------------------------------

    @Override
    @Transactional
    public ToolInfo create(Long operatorId, ToolCreateInput input) {
        if (input == null) {
            throw new ServiceException("工具内容不能为空——请在「工具管理」页点「新建工具」，填写「名称」与「类型」后提交", BizErrorCode.VALIDATION_ERROR);
        }
        String name = validateName(input.name());
        ToolType type = validateType(input.type());
        String body = input.body() == null ? null : input.body().trim();
        String description = input.description() == null ? null : input.description().trim();
        if (type == ToolType.DOC) {
            validateDocBody(body);
        }
        Integer execKindCode = null;
        if (type == ToolType.EXECUTABLE) {
            execKindCode = validateExecutable(input.execKind(), input.execSpecJson());
        }
        validateSchemas(input.inputSchemaJson(), input.outputSchemaJson());
        int timeoutMs = validateTimeout(input.timeoutMs());
        String tagsJson = blankToNull(input.tagsJson());
        if (tagsJson != null) {
            schemaValidator.parse("tags", tagsJson);
        }
        boolean executable = type == ToolType.EXECUTABLE;
        checkNameUnique(input.projectId(), name);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("projectId", input.projectId())
                .addValue("name", name)
                .addValue("type", type.code())
                .addValue("category", blankToNull(input.category()))
                .addValue("description", description)
                .addValue("body", blankToNull(body))
                .addValue("tags", tagsJson)
                .addValue("inputSchema", blankToNull(input.inputSchemaJson()))
                .addValue("outputSchema", blankToNull(input.outputSchemaJson()))
                .addValue("execKind", executable ? execKindCode : null)
                .addValue("execSpec", executable ? blankToNull(input.execSpecJson()) : null)
                .addValue("timeoutMs", executable ? timeoutMs : null)
                .addValue("status", ApprovalStatus.DRAFT.code())
                .addValue("operatorId", operatorId);
        Long id;
        try {
            id = named.queryForObject("""
                    INSERT INTO tp_tool (project_id, name, type, category, description, body,
                        tags, input_schema, output_schema, exec_kind, exec_spec, timeout_ms,
                        status, created_by)
                    VALUES (:projectId, :name, :type, :category, :description, :body,
                        :tags::jsonb, :inputSchema::jsonb, :outputSchema::jsonb, :execKind,
                        :execSpec::jsonb, :timeoutMs, :status, :operatorId)
                    RETURNING id
                    """, params, Long.class);
        } catch (DuplicateKeyException e) {
            throw conflictName(name);
        }
        ToolInfo created = get(id);
        recordAudit(operatorId, created.projectId(), id, "create", null, created);
        if (Boolean.TRUE.equals(input.submitNow())) {
            return transition(operatorId, id, ApprovalEvent.SUBMIT, created.version(), null, "submit");
        }
        return created;
    }

    @Override
    public ToolInfo get(Long id) {
        List<ToolInfo> rows = queryInfo("WHERE t.id = :id", new MapSqlParameterSource("id", id));
        if (rows.isEmpty()) {
            throw new ServiceException("工具不存在（id=" + id + "）——请返回「工具管理」列表刷新后重新打开；已删除的工具不再可见", BizErrorCode.NOT_FOUND);
        }
        return rows.get(0);
    }

    // ------------------------------------------------------------------
    // ------------------------------------------------------------------

    @Override
    public PageResult<ToolInfo> list(ToolQuery query) {
        int safePage = Math.max(query.page(), 1);
        int safeSize = PageQueryPolicy.enforceSize(query.size());
        long offset = PageQueryPolicy.clampOffset(safePage, safeSize, MAX_OFFSET);
        String sortColumn = SortColumnPolicy.require(query.sortBy(), SORT_COLUMNS, ENTITY_LABEL);
        if (sortColumn == null) {
            sortColumn = "t.created_at";
        }
        String sortDir = "asc".equalsIgnoreCase(query.sortDir()) ? "ASC" : "DESC";

        MapSqlParameterSource params = new MapSqlParameterSource();
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        if (query.keyword() != null && !query.keyword().isBlank()) {
            where.append(" AND (t.name ILIKE :keyword OR COALESCE(t.description, '') ILIKE :keyword)");
            params.addValue("keyword", "%" + query.keyword().trim() + "%");
        }
        if (query.type() != null) {
            where.append(" AND t.type = :type");
            params.addValue("type", query.type());
        }
        if (query.status() != null) {
            where.append(" AND t.status = :status");
            params.addValue("status", query.status());
        }
        if (query.projectId() != null) {
            // AR batch: project segment = own bucket ∪ system level (project_id IS
            // NULL). Other projects' private rows still never appear (H1).
            where.append(" AND (t.project_id = :projectId OR t.project_id IS NULL)");
            params.addValue("projectId", query.projectId());
        }
        if (query.category() != null && !query.category().isBlank()) {
            where.append(" AND t.category = :category");
            params.addValue("category", query.category().trim());
        }
        Long total = named.queryForObject(
                "SELECT count(*) FROM tp_tool t" + where, params, Long.class);
        params.addValue("size", safeSize).addValue("offset", offset);
        List<ToolInfo> list = queryInfo(where + " ORDER BY " + sortColumn + " " + sortDir
                + ", t.id DESC LIMIT :size OFFSET :offset", params);
        return new PageResult<>(list, total != null ? total : 0L);
    }

    // ------------------------------------------------------------------
    // ------------------------------------------------------------------

    @Override
    @Transactional
    public ToolInfo update(Long operatorId, Long id, ToolUpdateInput input) {
        if (input == null) {
            throw new ServiceException("工具编辑内容不能为空——请在工具详情点「编辑」，在「编辑工具」弹窗中至少修改一项内容后保存", BizErrorCode.VALIDATION_ERROR);
        }
        ToolRow current = queryRow(id);
        if (current.getStatus() == ApprovalStatus.RETIRED.code()) {
            throw new ServiceException("工具已终止（" + ApprovalStatus.labelOf(current.getStatus())
                    + "），不能编辑；如需复用请在「工具管理」页点「新建工具」按原内容重建", BizErrorCode.VALIDATION_ERROR);
        }
        boolean executable = current.getType() == ToolType.EXECUTABLE.code();
        String name = input.name() == null ? current.getName() : validateName(input.name());
        String body = input.body() == null ? current.getBody() : input.body().trim();
        if (executable && input.execKind() != null) {
            validateExecKind(input.execKind());
        }
        if (executable && input.execSpecJson() != null) {
            validateExecSpec(input.execKind() == null ? current.getExecKind() : input.execKind(),
                    input.execSpecJson());
        }
        if (executable && input.timeoutMs() != null) {
            validateTimeout(input.timeoutMs());
        }
        if (input.inputSchemaJson() != null) {
            schemaValidator.validateSchema("input schema", input.inputSchemaJson());
        }
        if (input.outputSchemaJson() != null) {
            schemaValidator.validateSchema("output schema", input.outputSchemaJson());
        }
        if (input.tagsJson() != null) {
            schemaValidator.parse("tags", input.tagsJson());
        }
        if (!name.equals(current.getName())) {
            checkNameUniqueExcept(current.getProjectId(), name, id);
        }
        Integer execKind = input.execKind() == null ? current.getExecKind() : input.execKind();
        String execSpec = blankToNull(input.execSpecJson()) == null
                ? current.getExecSpecJson() : input.execSpecJson().trim();
        Integer timeoutMs = input.timeoutMs() == null ? current.getTimeoutMs() : input.timeoutMs();
        String inputSchema = input.inputSchemaJson() == null
                ? current.getInputSchemaJson() : input.inputSchemaJson().trim();
        String outputSchema = input.outputSchemaJson() == null
                ? current.getOutputSchemaJson() : input.outputSchemaJson().trim();
        boolean contentChanged = changedText(body, current.getBody())
                || changedJson(inputSchema, current.getInputSchemaJson())
                || changedJson(outputSchema, current.getOutputSchemaJson())
                || !Objects.equals(execKind, current.getExecKind())
                || !schemaValidator.sameJson(execSpec, current.getExecSpecJson())
                || !Objects.equals(timeoutMs, current.getTimeoutMs());
        boolean published = ApprovalStateMachine.isPublished(current.getStatus());

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("expected", input.expectedVersion())
                .addValue("name", name)
                .addValue("category", input.category() == null
                        ? current.getCategory() : blankToNull(input.category().trim()))
                .addValue("description", input.description() == null
                        ? current.getDescription() : blankToNull(input.description().trim()))
                .addValue("body", blankToNull(body))
                .addValue("tags", input.tagsJson() == null
                        ? current.getTagsJson() : blankToNull(input.tagsJson()))
                .addValue("inputSchema", blankToNull(inputSchema))
                .addValue("outputSchema", blankToNull(outputSchema))
                .addValue("execKind", executable ? execKind : null)
                .addValue("execSpec", executable ? blankToNull(execSpec) : null)
                .addValue("timeoutMs", executable ? timeoutMs : null)
                .addValue("operatorId", operatorId);
        if (published && contentChanged) {
            insertSnapshot(operatorId, current, "edit");
            ApprovalStateMachine.transition(current.getStatus(), ApprovalEvent.SUBMIT, ENTITY_LABEL);
            if (named.update("""
                    UPDATE tp_tool SET name = :name, category = :category, description = :description,
                        body = :body, tags = :tags::jsonb, input_schema = :inputSchema::jsonb,
                        output_schema = :outputSchema::jsonb, exec_kind = :execKind,
                        exec_spec = :execSpec::jsonb, timeout_ms = :timeoutMs,
                        status = :pending, current_version = current_version + 1,
                        version = version + 1, approved_by = NULL, approved_at = NULL,
                        reject_reason = NULL, updated_at = now()
                    WHERE id = :id AND version = :expected
                    """, params.addValue("pending", ApprovalStatus.PENDING_APPROVAL.code())) == 0) {
                throw versionConflict(current.getVersion());
            }
        } else if (published) {
            if (named.update("""
                    UPDATE tp_tool SET name = :name, category = :category,
                        description = :description, tags = :tags::jsonb,
                        version = version + 1, updated_at = now()
                    WHERE id = :id AND version = :expected
                    """, params) == 0) {
                throw versionConflict(current.getVersion());
            }
        } else {
            if (named.update("""
                    UPDATE tp_tool SET name = :name, category = :category,
                        description = :description, body = :body, tags = :tags::jsonb,
                        input_schema = :inputSchema::jsonb, output_schema = :outputSchema::jsonb,
                        exec_kind = :execKind, exec_spec = :execSpec::jsonb,
                        timeout_ms = :timeoutMs, version = version + 1, updated_at = now()
                    WHERE id = :id AND version = :expected
                    """, params) == 0) {
                throw versionConflict(current.getVersion());
            }
        }
        ToolInfo after = get(id);
        recordAudit(operatorId, after.projectId(), id, "update",
                Map.of("status", current.getStatus(),
                        "statusLabel", ApprovalStatus.labelOf(current.getStatus()),
                        "name", current.getName(), "currentVersion", current.getCurrentVersion()),
                after);
        if (published && contentChanged) {
            toolApprovalNotifier.notifySubmitted(operatorId, after, true);
        }
        return after;
    }

    // ------------------------------------------------------------------
    // ------------------------------------------------------------------

    @Override
    @Transactional
    public void delete(Long operatorId, Long id, int expectedVersion) {
        ToolRow current = queryRow(id);
        if (ApprovalStateMachine.isPublished(current.getStatus())) {
            List<String> boundProjects = named.queryForList("""
                    SELECT p.name FROM tp_project_tool pt
                    JOIN tp_project p ON p.id = pt.project_id
                    WHERE pt.tool_id = :id ORDER BY p.name
                    """, new MapSqlParameterSource("id", current.getId()), String.class);
            if (!boundProjects.isEmpty()) {
                throw new ServiceException("工具「" + current.getName() + "」已被 "
                        + boundProjects.size() + " 个项目启用（" + String.join("、", boundProjects)
                        + "），不能删除；请先在「项目管理」进入上述项目的「工具启用」页签关闭该工具的启用开关后再删除", BizErrorCode.RESOURCE_CONFLICT);
            }
            throw new ServiceException("已发布的工具「" + current.getName()
                    + "」不能直接删除：请先在工具详情点「下架」后再删除，或前往「删除审批」页提交删除审批单",
                    BizErrorCode.VALIDATION_ERROR);
        }
        int deleted = named.update("DELETE FROM tp_tool WHERE id = :id AND version = :expected",
                new MapSqlParameterSource("id", id).addValue("expected", expectedVersion));
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
                String.valueOf(id), "delete", before, Map.of("deleted", true)));
    }

    // ------------------------------------------------------------------
    // ------------------------------------------------------------------

    @Override
    @Transactional
    public ToolInfo submit(Long operatorId, Long id, int expectedVersion) {
        return transition(operatorId, id, ApprovalEvent.SUBMIT, expectedVersion, null, "submit");
    }

    @Override
    @Transactional
    public ToolInfo approve(Long operatorId, Long id, int expectedVersion) {
        ToolInfo current = get(id);
        if (ApprovalStateMachine.isPublished(current.status())) {
            return current;
        }
        return transition(operatorId, id, ApprovalEvent.APPROVE, expectedVersion, null, "approve");
    }

    @Override
    @Transactional
    public ToolInfo reject(Long operatorId, Long id, int expectedVersion, String reason) {
        if (reason == null || reason.trim().isEmpty()) {
            throw new ServiceException("拒绝必须填写原因（写清拒绝理由）——请在「审批拒绝」弹窗的「拒绝原因」中写明理由后点「确认拒绝」", BizErrorCode.VALIDATION_ERROR);
        }
        String trimmed = reason.trim();
        if (trimmed.length() > REASON_MAX) {
            throw new ServiceException("拒绝原因最长 " + REASON_MAX + " 字——请在「审批拒绝」弹窗把「拒绝原因」精简到限制字数以内", BizErrorCode.VALIDATION_ERROR);
        }
        return transition(operatorId, id, ApprovalEvent.REJECT, expectedVersion, trimmed, "reject");
    }

    @Override
    @Transactional
    public ToolInfo retire(Long operatorId, Long id, int expectedVersion) {
        insertSnapshot(operatorId, queryRow(id), "retire");
        return transition(operatorId, id, ApprovalEvent.RETIRE, expectedVersion, null, "retire");
    }

    // ------------------------------------------------------------------
    // ------------------------------------------------------------------

    @Override
    public PageResult<ToolVersionInfo> listVersions(Long id, int page, int size) {
        get(id);
        int safePage = Math.max(page, 1);
        int safeSize = PageQueryPolicy.enforceSize(size);
        Long total = named.queryForObject(
                "SELECT count(*) FROM tp_tool_version WHERE tool_id = :id",
                new MapSqlParameterSource("id", id), Long.class);
        MapSqlParameterSource params = new MapSqlParameterSource("id", id)
                .addValue("size", safeSize)
                .addValue("offset", PageQueryPolicy.clampOffset(safePage, safeSize, MAX_OFFSET));
        List<ToolVersionInfo> list = named.query(SELECT_VERSION_COLUMNS
                + " ORDER BY version DESC LIMIT :size OFFSET :offset", params,
                new BeanPropertyRowMapper<>(ToolVersionRow.class))
                .stream().map(ToolVersionRow::toInfo).toList();
        return new PageResult<>(list, total != null ? total : 0L);
    }

    @Override
    public ToolVersionDetail getVersionSnapshot(Long id, int version) {
        List<ToolVersionDetail> rows = named.query(SELECT_VERSION_COLUMNS
                + " AND version = :version", new MapSqlParameterSource("id", id)
                .addValue("version", version),
                new BeanPropertyRowMapper<>(ToolVersionRow.class))
                .stream().map(ToolVersionRow::toDetail).toList();
        if (rows.isEmpty()) {
            throw new ServiceException("工具版本快照不存在（id=" + id + ", v" + version + "）——请在工具详情「版本历史」页签选择列表中存在的版本后重试",
                    BizErrorCode.NOT_FOUND);
        }
        return rows.get(0);
    }

    // ------------------------------------------------------------------
    // ------------------------------------------------------------------

    @Override
    public ToolDetail getDetail(Long id) {
        return queryRow(id).toDetail();
    }

    @Override
    @Transactional
    public ToolDownloadFile download(Long operatorId, Long id, Integer version) {
        ToolRow current = queryRow(id);
        int target = version == null ? current.getCurrentVersion() : version;
        String slugName = slugify(current.getName());
        boolean doc = current.getType() == ToolType.DOC.code();
        byte[] content;
        String contentType;
        String ext;
        if (target == current.getCurrentVersion()) {
            content = docBytesOrBundle(current, doc);
            contentType = doc ? "text/markdown" : "application/json";
            ext = doc ? ".md" : ".tool-bundle.json";
        } else {
            ToolVersionDetail snap = getVersionSnapshot(id, target);
            content = docBytesOrBundle(snapshotToRow(snap, current), doc);
            contentType = doc ? "text/markdown" : "application/json";
            ext = doc ? ".md" : ".tool-bundle.json";
        }
        String fileName = slugName + "-v" + target + ext;
        auditLogService.record(new AuditLogInput(operatorId, current.getProjectId(), OBJECT_TYPE,
                String.valueOf(id), "tool-download", Map.of("name", current.getName()),
                Map.of("version", target, "fileName", fileName)));
        return new ToolDownloadFile(id, target, fileName, contentType, content);
    }

    @Override
    @Transactional
    public ToolInfo rollback(Long operatorId, Long id, ToolRollbackInput input) {
        ToolRow current = queryRow(id);
        if (current.getStatus() == ApprovalStatus.RETIRED.code()) {
            throw new ServiceException("工具已终止（" + ApprovalStatus.labelOf(current.getStatus())
                    + "），不能回滚；如需复用请在「工具管理」页点「新建工具」按快照内容重建", BizErrorCode.VALIDATION_ERROR);
        }
        if (input.version() == current.getCurrentVersion()) {
            throw new ServiceException("目标版本即当前版本（v" + input.version() + "），无需回滚——请在工具详情「版本历史」页签对更早版本点「回滚」发起",
                    BizErrorCode.VALIDATION_ERROR);
        }
        ToolVersionDetail snap = getVersionSnapshot(id, input.version());
        ApprovalStateMachine.transition(current.getStatus(), ApprovalEvent.SUBMIT, ENTITY_LABEL);
        insertSnapshot(operatorId, current, "rollback-pre");
        int updated = named.update("""
                UPDATE tp_tool SET body = :body, input_schema = :inputSchema::jsonb,
                    output_schema = :outputSchema::jsonb, exec_spec = :execSpec::jsonb,
                    timeout_ms = :timeoutMs, status = :pending,
                    current_version = current_version + 1, version = version + 1,
                    approved_by = NULL, approved_at = NULL, reject_reason = NULL,
                    updated_at = now()
                WHERE id = :id AND version = :expected
                """, new MapSqlParameterSource()
                .addValue("body", snap.body())
                .addValue("inputSchema", snap.inputSchemaJson())
                .addValue("outputSchema", snap.outputSchemaJson())
                .addValue("execSpec", snap.execSpecJson())
                .addValue("timeoutMs", snap.timeoutMs())
                .addValue("pending", ApprovalStatus.PENDING_APPROVAL.code())
                .addValue("id", id)
                .addValue("expected", input.expectedVersion()));
        if (updated == 0) {
            throw versionConflict(current.getVersion());
        }
        ToolInfo after = get(id);
        recordAudit(operatorId, after.projectId(), id, "tool-rollback",
                Map.of("currentVersion", current.getCurrentVersion(),
                        "status", current.getStatus(),
                        "statusLabel", ApprovalStatus.labelOf(current.getStatus()),
                        "rollbackTo", input.version()),
                after);
        toolApprovalNotifier.notifySubmitted(operatorId, after, true);
        return after;
    }

    /**
 * {@link com.sixbean.testpike.core.tool.domain.component.ToolPackSupport#contractFields}
 */
    private byte[] docBytesOrBundle(ToolRow row, boolean doc) {
        if (doc) {
            String body = row.getBody() == null ? "" : row.getBody();
            return body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
        return toolPackSupport.buildBundleJson(toolPackSupport.contractFields(
                row.getName(), row.getType(), row.getCategory(), row.getDescription(),
                row.getBody(), row.getTagsJson(), row.getInputSchemaJson(),
                row.getOutputSchemaJson(), row.getExecKind(), row.getExecSpecJson(),
                row.getTimeoutMs()));
    }

    
    private static ToolRow snapshotToRow(ToolVersionDetail snap, ToolRow current) {
        ToolRow merged = new ToolRow();
        merged.setId(current.getId());
        merged.setProjectId(current.getProjectId());
        merged.setName(current.getName());
        merged.setType(current.getType());
        merged.setCategory(current.getCategory());
        merged.setDescription(current.getDescription());
        merged.setBody(snap.body());
        merged.setTagsJson(current.getTagsJson());
        merged.setInputSchemaJson(snap.inputSchemaJson());
        merged.setOutputSchemaJson(snap.outputSchemaJson());
        merged.setExecKind(current.getExecKind());
        merged.setExecSpecJson(snap.execSpecJson());
        merged.setTimeoutMs(snap.timeoutMs());
        merged.setStatus(current.getStatus());
        merged.setCurrentVersion(snap.version());
        merged.setVersion(current.getVersion());
        return merged;
    }

    
    private static String slugify(String name) {
        String slug = name == null ? "tool" : name.trim().toLowerCase()
                .replaceAll("[/\\\\:?*\"<>|\\s]+", "-")
                .replaceAll("^-+|-+$", "");
        return slug.isEmpty() ? "tool" : slug;
    }

    
    private void insertSnapshot(Long operatorId, ToolRow current, String reason) {
        named.update("""
                INSERT INTO tp_tool_version (tool_id, version, body, input_schema,
                    output_schema, exec_spec, timeout_ms, status_at_snapshot, reason, created_by)
                VALUES (:toolId, :version, :body, :inputSchema::jsonb, :outputSchema::jsonb,
                    :execSpec::jsonb, :timeoutMs, :statusAtSnapshot, :reason, :operatorId)
                """, new MapSqlParameterSource()
                .addValue("toolId", current.getId())
                .addValue("version", current.getCurrentVersion())
                .addValue("body", current.getBody())
                .addValue("inputSchema", current.getInputSchemaJson())
                .addValue("outputSchema", current.getOutputSchemaJson())
                .addValue("execSpec", current.getExecSpecJson())
                .addValue("timeoutMs", current.getTimeoutMs())
                .addValue("statusAtSnapshot", current.getStatus())
                .addValue("reason", reason)
                .addValue("operatorId", operatorId));
    }

    // ------------------------------------------------------------------
    // ------------------------------------------------------------------

    private ToolInfo transition(Long operatorId, Long id, ApprovalEvent event,
                                int expectedVersion, String rejectReason, String auditAction) {
        ToolInfo current = get(id);
        int target = ApprovalStateMachine.transition(current.status(), event, ENTITY_LABEL);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("target", target)
                .addValue("id", id)
                .addValue("expected", expectedVersion);
        StringBuilder sql = new StringBuilder("""
                UPDATE tp_tool SET status = :target, version = version + 1, updated_at = now()
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
            throw versionConflict(current.version());
        }
        ToolInfo after = get(id);
        recordAudit(operatorId, after.projectId(), id, auditAction,
                Map.of("status", current.status(), "statusLabel", current.statusLabel()), after);
        if (event == ApprovalEvent.SUBMIT) {
            toolApprovalNotifier.notifySubmitted(operatorId, after, false);
        } else if (target == ApprovalStatus.PUBLISHED.code()) {
            toolApprovalNotifier.notifyApproved(operatorId, after);
        } else if (target == ApprovalStatus.REJECTED.code()) {
            toolApprovalNotifier.notifyRejected(operatorId, after);
        }
        return after;
    }

    // ------------------------------------------------------------------
    // ------------------------------------------------------------------

    private String validateName(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            throw new ServiceException("工具名称不能为空——请在「新建工具」弹窗填写「名称」后保存", BizErrorCode.VALIDATION_ERROR);
        }
        String name = raw.trim();
        if (name.length() > NAME_MAX) {
            throw new ServiceException("工具名称最长 " + NAME_MAX + " 字（当前 "
                    + name.length() + " 字）——请在「新建工具」弹窗把「名称」缩短到限制字数以内", BizErrorCode.VALIDATION_ERROR);
        }
        return name;
    }

    private ToolType validateType(Integer raw) {
        if (raw == null) {
            throw new ServiceException("工具类型不能为空（可选：" + ToolType.allLabels() + "）——请在「新建工具」弹窗的「类型」中选择「文档类」或「可执行」",
                    BizErrorCode.VALIDATION_ERROR);
        }
        ToolType type = ToolType.of(raw);
        if (type == null) {
            throw new ServiceException("工具类型 " + raw + " 不在合法枚举（可选："
                    + ToolType.allLabels() + "）——请在「新建工具」弹窗的「类型」下拉改选列表中的合法类型", BizErrorCode.VALIDATION_ERROR);
        }
        return type;
    }

    private void validateDocBody(String body) {
        if (body == null || body.isBlank()) {
            throw new ServiceException("文档类工具正文（body）不能为空——请在「新建工具」弹窗选择「文档类」后在「文档正文」填写 Markdown 内容", BizErrorCode.VALIDATION_ERROR);
        }
    }

    
    private int validateExecutable(Integer execKind, String execSpecJson) {
        int kind = validateExecKind(execKind);
        if (execSpecJson == null || execSpecJson.isBlank()) {
            throw new ServiceException("可执行工具必须提供执行配置——请在「新建工具」弹窗选择「可执行」后填写「执行配置」（"
                    + ToolExecKind.labelOf(kind) + " 形态所需字段见输入框占位示例）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        validateExecSpec(kind, execSpecJson);
        return kind;
    }

    private int validateExecKind(Integer raw) {
        if (raw == null) {
            throw new ServiceException("可执行工具必须选择执行形态（可选："
                    + ToolExecKind.allLabels() + "）——请在「新建工具」弹窗的「执行形态」下拉选择列表中的合法形态", BizErrorCode.VALIDATION_ERROR);
        }
        ToolExecKind kind = ToolExecKind.of(raw);
        if (kind == null) {
            throw new ServiceException("执行形态 " + raw + " 不在合法枚举（可选："
                    + ToolExecKind.allLabels() + "）——请在「新建工具」弹窗的「执行形态」下拉改选列表中的合法形态", BizErrorCode.VALIDATION_ERROR);
        }
        return kind.code();
    }

    
    private void validateExecSpec(int execKindCode, String execSpecJson) {
        JsonNode spec = schemaValidator.parse("执行配置", execSpecJson);
        ToolExecKind kind = ToolExecKind.of(execKindCode);
        if (kind == ToolExecKind.HTTP && !hasText(spec, "url")) {
            throw new ServiceException("HTTP 执行配置缺少 url（exec_spec 需含 {url, authRef?}）——请在「执行配置」中补写 url，正确写法示例：{\"url\":\"https://api.example.com/check\"}",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (kind == ToolExecKind.EXECUTOR && !hasText(spec, "scriptEntry")) {
            throw new ServiceException("EXECUTOR 执行配置缺少 scriptEntry（执行器沙箱脚本入口路径）——请在「执行配置」中补写 scriptEntry，正确写法示例：{\"scriptEntry\":\"flows/login.py\"}",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (kind == ToolExecKind.MCP_BRIDGE) {
            if (!spec.hasNonNull("serverId")) {
                throw new ServiceException("MCP_BRIDGE 执行配置缺少 serverId（tp_mcp_server 引用）——请在「执行配置」中补写 serverId，正确写法示例：{\"serverId\":1,\"toolName\":\"query_order\"}",
                        BizErrorCode.VALIDATION_ERROR);
            }
            if (!hasText(spec, "toolName")) {
                throw new ServiceException("MCP_BRIDGE 执行配置缺少 toolName（远端工具名）——请在「执行配置」中补写 toolName，正确写法示例：{\"serverId\":1,\"toolName\":\"query_order\"}",
                        BizErrorCode.VALIDATION_ERROR);
            }
        }
        if (kind == ToolExecKind.GENKIT && !hasText(spec, "function")) {
            throw new ServiceException("GENKIT 执行配置缺少 function（genkit 注册表函数名）——"
                    + "请在「执行配置」中按格式填写：{\"function\":\"idCard\"}",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (kind == ToolExecKind.ANALYSIS && !hasText(spec, "function")) {
            throw new ServiceException("只读分析执行配置缺少 function（分析函数注册表函数名）——"
                    + "请在「执行配置」中按格式填写：{\"function\":\"coverageSummary\"}",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    private void validateSchemas(String inputSchema, String outputSchema) {
        schemaValidator.validateSchema("input schema", inputSchema);
        schemaValidator.validateSchema("output schema", outputSchema);
    }

    private int validateTimeout(Integer raw) {
        if (raw == null) {
            return TIMEOUT_DEFAULT_MS;
        }
        if (raw > TIMEOUT_MAX_MS) {
            throw new ServiceException("执行超时上限 " + TIMEOUT_MAX_MS
                    + " 毫秒（当前 " + raw + "），请拆分步骤或精简流程，可在「新建工具」弹窗把「执行超时(ms)」改小后保存", BizErrorCode.VALIDATION_ERROR);
        }
        if (raw <= 0) {
            throw new ServiceException("执行超时必须为正整数毫秒——请在「新建工具」弹窗的「执行超时(ms)」中填写大于 0 的整数毫秒数", BizErrorCode.VALIDATION_ERROR);
        }
        return raw;
    }

    private static boolean hasText(JsonNode node, String field) {
        return node.hasNonNull(field) && !node.get(field).asText().isBlank();
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
                "SELECT count(*) FROM tp_tool WHERE " + where + " AND name = :name",
                params, Integer.class);
        if (n != null && n > 0) {
            throw conflictName(name);
        }
    }

    private ServiceException conflictName(String name) {
        return new ServiceException("工具名称「" + name + "」已存在（同一项目/系统级内不允许重名）——请改换一个名称，或先在「工具管理」列表处理同名旧工具后重建",
                BizErrorCode.RESOURCE_CONFLICT);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    
    private static boolean changedText(String newVal, String currentVal) {
        return newVal != null && !Objects.equals(newVal.trim(), currentVal);
    }

    
    private boolean changedJson(String newVal, String currentVal) {
        return newVal != null && !schemaValidator.sameJson(newVal.trim(), currentVal);
    }

    // ------------------------------------------------------------------
    // ------------------------------------------------------------------

    
    private ToolRow queryRow(Long id) {
        List<ToolRow> rows = named.query(SELECT_COLUMNS + " WHERE t.id = :id",
                new MapSqlParameterSource("id", id), new BeanPropertyRowMapper<>(ToolRow.class));
        if (rows.isEmpty()) {
            throw new ServiceException("工具不存在（id=" + id + "）——请返回「工具管理」列表刷新后重新打开；已删除的工具不再可见", BizErrorCode.NOT_FOUND);
        }
        return rows.get(0);
    }

    private ServiceException versionConflict(int currentVersion) {
        return new ServiceException("工具已被他人修改，请刷新后重试（当前版本 "
                + currentVersion + "）——请返回「工具管理」列表刷新该行，或重新打开工具详情后再保存", BizErrorCode.VERSION_CONFLICT);
    }

    
    private List<ToolInfo> queryInfo(String whereClause, MapSqlParameterSource params) {
        return named.query(SELECT_COLUMNS + " " + whereClause, params,
                        new BeanPropertyRowMapper<>(ToolRow.class))
                .stream().map(ToolRow::toInfo).toList();
    }

    
    private void recordAudit(Long operatorId, Long projectId, Long toolId, String action,
                             Map<String, Object> before, ToolInfo after) {
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
                String.valueOf(toolId), action, before, afterMap));
    }
}
