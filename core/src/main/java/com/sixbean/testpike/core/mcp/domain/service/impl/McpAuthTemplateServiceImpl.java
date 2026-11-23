package com.sixbean.testpike.core.mcp.domain.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.audit.api.dto.AuditLogInput;
import com.sixbean.testpike.core.audit.domain.service.AuditLogService;
import com.sixbean.testpike.core.common.domain.component.ViewFormats;
import com.sixbean.testpike.core.mcp.api.dto.McpAuthApplyInput;
import com.sixbean.testpike.core.mcp.api.dto.McpAuthApplyResult;
import com.sixbean.testpike.core.mcp.api.dto.McpAuthTemplateInfo;
import com.sixbean.testpike.core.mcp.api.dto.McpAuthTemplateInput;
import com.sixbean.testpike.core.mcp.api.dto.ProjectMcpAuthInfo;
import com.sixbean.testpike.core.mcp.api.dto.ProjectMcpAuthInput;
import com.sixbean.testpike.core.mcp.domain.component.McpToolSnapshot;
import com.sixbean.testpike.core.mcp.domain.service.McpAuthTemplateService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;


@Service
public class McpAuthTemplateServiceImpl implements McpAuthTemplateService {

    
    static final int NAME_MAX = 100;
    
    static final int TOOLS_MAX = 500;
    
    static final int TOOL_NAME_MAX = 100;
    
    static final int REMARK_MAX = 500;
    private static final String OBJECT_TYPE = "mcp_auth_template";

    private final NamedParameterJdbcTemplate named;
    private final AuditLogService auditLogService;

    public McpAuthTemplateServiceImpl(NamedParameterJdbcTemplate named,
                                      AuditLogService auditLogService) {
        this.named = named;
        this.auditLogService = auditLogService;
    }

    @Override
    public List<McpAuthTemplateInfo> listTemplates() {
        return named.query(SELECT_COLUMNS + " ORDER BY t.id ASC",
                new MapSqlParameterSource(), McpAuthTemplateServiceImpl::mapTemplate);
    }

    @Override
    @Transactional
    public McpAuthTemplateInfo create(Long operatorId, McpAuthTemplateInput input) {
        if (input == null) {
            throw new ServiceException("授权模板内容不能为空：请在「MCP 服务」页的「授权模板」标签点「新建模板」，填写名称与工具清单后提交",
                    BizErrorCode.VALIDATION_ERROR);
        }
        String name = validateName(input.name());
        List<String> tools = validateTools(input.tools());
        int scope = validateScope(input.scope(), input.projectId());
        checkNameUnique(name, null);
        Long id;
        try {
            id = named.queryForObject("""
                    INSERT INTO tp_mcp_auth_template (name, tools, remark, scope, project_id)
                    VALUES (:name, :tools::jsonb, :remark, :scope, :projectId)
                    RETURNING id
                    """, new MapSqlParameterSource()
                    .addValue("name", name)
                    .addValue("tools", toolsJson(tools))
                    .addValue("remark", validateRemark(input.remark()))
                    .addValue("scope", scope)
                    .addValue("projectId", scope == 2 ? input.projectId() : null), Long.class);
        } catch (DuplicateKeyException e) {
            throw conflictName(name);
        }
        McpAuthTemplateInfo created = getTemplate(id);
        recordAudit(operatorId, id, "create", null, auditSnapshot(created));
        return created;
    }

    @Override
    @Transactional
    public McpAuthTemplateInfo update(Long operatorId, Long id, McpAuthTemplateInput input) {
        if (input == null) {
            throw new ServiceException("授权模板编辑内容不能为空：请在模板列表该行点「编辑」，打开「编辑授权模板」修改后保存",
                    BizErrorCode.VALIDATION_ERROR);
        }
        McpAuthTemplateInfo current = getTemplate(id);
        String name = input.name() == null ? current.name() : validateName(input.name());
        List<String> tools = input.tools() == null ? current.tools() : validateTools(input.tools());
        int scope = input.scope() == null ? current.scope() : validateScope(input.scope(), input.projectId());
        Long projectId = scope == 2
                ? (input.projectId() != null ? input.projectId() : current.projectId()) : null;
        if (scope == 2 && projectId == null) {
            throw new ServiceException("项目级模板必须指定所属项目：请在「作用域」选「项目级」后在「所属项目」下拉选择，接口调用请传 projectId=数字项目ID",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (!name.equals(current.name())) {
            checkNameUnique(name, id);
        }
        try {
            named.update("""
                    UPDATE tp_mcp_auth_template
                    SET name = :name, tools = :tools::jsonb, remark = :remark,
                        scope = :scope, project_id = :projectId, updated_at = now()
                    WHERE id = :id
                    """, new MapSqlParameterSource()
                    .addValue("id", id)
                    .addValue("name", name)
                    .addValue("tools", toolsJson(tools))
                    .addValue("remark", validateRemark(input.remark()))
                    .addValue("scope", scope)
                    .addValue("projectId", projectId));
        } catch (DuplicateKeyException e) {
            throw conflictName(name);
        }
        McpAuthTemplateInfo after = getTemplate(id);
        recordAudit(operatorId, id, "update",
                Map.of("name", current.name(), "tools", current.tools()), auditSnapshot(after));
        return after;
    }

    @Override
    @Transactional
    public void delete(Long operatorId, Long id) {
        McpAuthTemplateInfo current = getTemplate(id);
        Integer inheriting = named.queryForObject(
                "SELECT count(*) FROM tp_project_mcp_auth WHERE template_id = :id",
                new MapSqlParameterSource("id", id), Integer.class);
        if (inheriting != null && inheriting > 0) {
            throw new ServiceException("授权模板「" + current.name() + "」已被 " + inheriting
                    + " 个项目的工具继承引用，不能删除；请前往「授权模板」标签的「项目继承配置」卡片解除引用或改选其它模板",
                    BizErrorCode.RESOURCE_CONFLICT);
        }
        named.update("DELETE FROM tp_mcp_auth_template WHERE id = :id",
                new MapSqlParameterSource("id", id));
        recordAudit(operatorId, id, "delete",
                Map.of("name", current.name(), "tools", current.tools()), null);
    }

    @Override
    @Transactional
    public McpAuthApplyResult apply(Long operatorId, Long templateId, McpAuthApplyInput input) {
        if (input == null || input.serverIds() == null || input.serverIds().isEmpty()) {
            throw new ServiceException("套用的目标服务清单不能为空：请在「批量套用」对话框勾选目标 MCP 服务后再点「套用到所选服务」",
                    BizErrorCode.VALIDATION_ERROR);
        }
        McpAuthTemplateInfo template = getTemplate(templateId);
        Set<String> toolNames = new LinkedHashSet<>(template.tools());
        List<String> details = new ArrayList<>();
        int updated = 0;
        for (Long serverId : input.serverIds()) {
            if (serverId == null) {
                details.add("服务ID 空缺已忽略，请检查提交内容");
                continue;
            }
            List<Map<String, Object>> rows = named.queryForList(
                    "SELECT name, tools_json::text AS tools_json FROM tp_mcp_server WHERE id = :id",
                    new MapSqlParameterSource("id", serverId));
            if (rows.isEmpty()) {
                details.add("服务(id=" + serverId + ") 不存在，已跳过");
                continue;
            }
            Map<String, Object> row = rows.get(0);
            String serverName = (String) row.get("name");
            JsonNode snapshot = McpToolSnapshot.parse((String) row.get("tools_json"));
            if (snapshot == null) {
                details.add("服务" + serverName + "：未获取工具清单，请先「连接测试」后再套用模板");
                continue;
            }
            Set<String> snapshotNames = new LinkedHashSet<>();
            snapshot.forEach(node -> snapshotNames.add(node.path("name").asText("")));
            List<String> skipped = toolNames.stream().filter(n -> !snapshotNames.contains(n)).toList();
            int enabledNow = McpToolSnapshot.enableOnly(snapshot, toolNames);
            named.update("UPDATE tp_mcp_server SET tools_json = :snapshot::jsonb, updated_at = now() "
                            + "WHERE id = :id",
                    new MapSqlParameterSource("id", serverId)
                            .addValue("snapshot", snapshot.toString()));
            updated++;
            StringBuilder line = new StringBuilder("服务").append(serverName)
                    .append("：新启用").append(enabledNow).append("个");
            if (!skipped.isEmpty()) {
                line.append("；跳过(快照外)：").append(String.join("、", skipped));
            }
            details.add(line.toString());
        }
        recordAudit(operatorId, templateId, "apply",
                Map.of("template", template.name(), "serverIds", input.serverIds()),
                Map.of("updatedServers", updated));
        return new McpAuthApplyResult(updated, List.copyOf(details));
    }

    @Override
    public ProjectMcpAuthInfo getProjectAuth(Long projectId) {
        List<ProjectMcpAuthInfo> rows = named.query("""
                SELECT a.project_id, a.template_id, t.name AS template_name, a.enabled
                FROM tp_project_mcp_auth a
                LEFT JOIN tp_mcp_auth_template t ON t.id = a.template_id
                WHERE a.project_id = :projectId
                """, new MapSqlParameterSource("projectId", projectId),
                McpAuthTemplateServiceImpl::mapProjectAuth);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    @Transactional
    public ProjectMcpAuthInfo putProjectAuth(Long operatorId, Long projectId,
                                             ProjectMcpAuthInput input) {
        if (projectId == null) {
            throw new ServiceException("项目ID 不能为空：请先在「项目继承配置」卡片的「选择项目」下拉选中项目再保存",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (input == null || input.templateId() == null) {
            throw new ServiceException("请先选择要继承的授权模板：在「项目继承配置」的「模板」下拉选择；没有模板时先到「授权模板」标签点「新建模板」",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (input.enabled() == null || (input.enabled() != 0 && input.enabled() != 1)) {
            throw new ServiceException("继承开关值 " + input.enabled() + " 非法（1=按模板交集 0=停用继承）：请用「继承开关」切换，接口请传 enabled=1 或 0",
                    BizErrorCode.VALIDATION_ERROR);
        }
        getTemplate(input.templateId());
        named.update("""
                INSERT INTO tp_project_mcp_auth (project_id, template_id, enabled)
                VALUES (:projectId, :templateId, :enabled)
                ON CONFLICT (project_id)
                DO UPDATE SET template_id = EXCLUDED.template_id, enabled = EXCLUDED.enabled,
                              updated_at = now()
                """, new MapSqlParameterSource()
                .addValue("projectId", projectId)
                .addValue("templateId", input.templateId())
                .addValue("enabled", input.enabled()));
        recordAudit(operatorId, projectId, "project_auth",
                Map.of("templateId", input.templateId()), Map.of("enabled", input.enabled()));
        return getProjectAuth(projectId);
    }

    
    private McpAuthTemplateInfo getTemplate(Long id) {
        List<McpAuthTemplateInfo> rows = named.query(
                SELECT_COLUMNS + " WHERE t.id = :id",
                new MapSqlParameterSource("id", id), McpAuthTemplateServiceImpl::mapTemplate);
        if (rows.isEmpty()) {
            throw new ServiceException("授权模板不存在（id=" + id + "）：可能已被删除，请回到「授权模板」列表刷新确认后再操作",
                    BizErrorCode.NOT_FOUND);
        }
        return rows.get(0);
    }

    private static final String SELECT_COLUMNS = """
            SELECT t.id, t.name, t.tools::text AS tools, t.remark, t.scope, t.project_id,
                   t.created_at, t.updated_at
            FROM tp_mcp_auth_template t
            """;

    
    private static McpAuthTemplateInfo mapTemplate(ResultSet rs, int i) throws java.sql.SQLException {
        JsonNode tools = McpToolSnapshot.parse(rs.getString("tools"));
        List<String> names = new ArrayList<>();
        if (tools != null) {
            tools.forEach(n -> names.add(n.asText()));
        }
        Timestamp createdAt = rs.getTimestamp("created_at");
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        return new McpAuthTemplateInfo(rs.getLong("id"), rs.getString("name"), List.copyOf(names),
                rs.getString("remark"), rs.getInt("scope"),
                rs.getObject("project_id", Long.class),
                ViewFormats.formatTs(createdAt), ViewFormats.formatTs(updatedAt));
    }

    private static ProjectMcpAuthInfo mapProjectAuth(ResultSet rs, int i) throws java.sql.SQLException {
        return new ProjectMcpAuthInfo(rs.getLong("project_id"), rs.getLong("template_id"),
                rs.getString("template_name"), rs.getInt("enabled"));
    }

    // ------------------------------------------------------------------
    // ------------------------------------------------------------------

    private String validateName(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            throw new ServiceException("授权模板名称不能为空：请在「新建模板」的「模板名」输入框填写", BizErrorCode.VALIDATION_ERROR);
        }
        String name = raw.trim();
        if (name.length() > NAME_MAX) {
            throw new ServiceException("授权模板名称最长 " + NAME_MAX + " 字（当前 "
                    + name.length() + " 字），请精简「模板名」后重新保存", BizErrorCode.VALIDATION_ERROR);
        }
        return name;
    }

    private List<String> validateTools(List<String> rawTools) {
        if (rawTools == null || rawTools.isEmpty()) {
            throw new ServiceException("授权模板的工具清单不能为空（空清单模板套用无意义）：请在「新建模板」的「工具清单」选择或手输至少一个工具名",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (rawTools.size() > TOOLS_MAX) {
            throw new ServiceException("单个授权模板工具清单最长 " + TOOLS_MAX + " 条（当前 "
                    + rawTools.size() + " 条），请精简「工具清单」或分批拆成多个模板", BizErrorCode.VALIDATION_ERROR);
        }
        Set<String> names = new LinkedHashSet<>();
        for (String raw : rawTools) {
            if (raw == null || raw.isBlank()) {
                throw new ServiceException("工具清单含空工具名：请在「工具清单」中删除空条目后重新提交", BizErrorCode.VALIDATION_ERROR);
            }
            String name = raw.trim();
            if (name.length() > TOOL_NAME_MAX) {
                throw new ServiceException("工具名称最长 " + TOOL_NAME_MAX + " 字（当前 "
                        + name.length() + " 字），请在「工具清单」中改正该条目后重新提交", BizErrorCode.VALIDATION_ERROR);
            }
            names.add(name);
        }
        return List.copyOf(names);
    }

    private int validateScope(Integer scope, Long projectId) {
        int code = scope == null ? 1 : scope;
        if (code != 1 && code != 2) {
            throw new ServiceException("模板范围 " + scope + " 非法（1=系统级 2=项目级）：请在「作用域」单选 系统级 或 项目级，接口请传 scope=1 或 2",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (code == 2 && projectId == null) {
            throw new ServiceException("项目级模板必须指定所属项目：请在「作用域」选「项目级」后在「所属项目」下拉选择，接口调用请传 projectId=数字项目ID",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return code;
    }

    private String validateRemark(String remark) {
        if (remark == null || remark.isBlank()) {
            return null;
        }
        String trimmed = remark.trim();
        if (trimmed.length() > REMARK_MAX) {
            throw new ServiceException("模板备注最长 " + REMARK_MAX + " 字（当前 "
                    + trimmed.length() + " 字），请在「备注」中精简后保存", BizErrorCode.VALIDATION_ERROR);
        }
        return trimmed;
    }

    
    private static String toolsJson(List<String> tools) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < tools.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(tools.get(i).replace("\\", "\\\\").replace("\"", "\\\""))
                    .append('"');
        }
        return sb.append(']').toString();
    }

    private void checkNameUnique(String name, Long excludeId) {
        String where = "name = :name";
        MapSqlParameterSource params = new MapSqlParameterSource("name", name);
        if (excludeId != null) {
            where += " AND id <> :excludeId";
            params.addValue("excludeId", excludeId);
        }
        Integer n = named.queryForObject(
                "SELECT count(*) FROM tp_mcp_auth_template WHERE " + where, params, Integer.class);
        if (n != null && n > 0) {
            throw conflictName(name);
        }
    }

    private ServiceException conflictName(String name) {
        return new ServiceException("授权模板名称「" + name + "」已存在（全局模板名不允许重名）：请在「模板名」输入框改用其它名称（如加用途后缀「只读查询集-2」）",
                BizErrorCode.RESOURCE_CONFLICT);
    }

    
    private static Map<String, Object> auditSnapshot(McpAuthTemplateInfo info) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("id", info.id());
        map.put("name", info.name());
        map.put("tools", info.tools());
        map.put("scope", info.scope());
        map.put("projectId", info.projectId());
        return map;
    }

    
    private void recordAudit(Long operatorId, Long id, String action,
                             Map<String, Object> before, Map<String, Object> after) {
        auditLogService.record(new AuditLogInput(operatorId, null, OBJECT_TYPE,
                String.valueOf(id), action, before, after));
    }
}
