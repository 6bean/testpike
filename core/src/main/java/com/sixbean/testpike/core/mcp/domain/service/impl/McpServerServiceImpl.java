package com.sixbean.testpike.core.mcp.domain.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.audit.api.dto.AuditLogInput;
import com.sixbean.testpike.core.audit.domain.service.AuditLogService;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.common.domain.component.PageQueryPolicy;
import com.sixbean.testpike.core.common.domain.component.SortColumnPolicy;
import com.sixbean.testpike.core.config.domain.component.ConfigCipher;
import com.sixbean.testpike.core.mcp.api.dto.McpServerCreateInput;
import com.sixbean.testpike.core.mcp.api.dto.McpServerInfo;
import com.sixbean.testpike.core.mcp.api.dto.McpServerQuery;
import com.sixbean.testpike.core.mcp.api.dto.McpServerUpdateInput;
import com.sixbean.testpike.core.mcp.api.dto.McpToolSelectionInput;
import com.sixbean.testpike.core.mcp.domain.component.McpEndpointPolicy;
import com.sixbean.testpike.core.mcp.domain.component.McpToolSnapshot;
import com.sixbean.testpike.core.mcp.domain.component.McpTransport;
import com.sixbean.testpike.core.mcp.domain.service.McpServerService;
import com.sixbean.testpike.core.mcp.infrastructure.dataobject.McpServerRow;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;


@Service
public class McpServerServiceImpl implements McpServerService {

 
    static final int NAME_MAX = 100;
 
    static final int ENDPOINT_MAX = 500;
 
    static final int AUTH_MAX_BYTES = 4 * 1024;
    
    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "id", "m.id",
            "name", "m.name",
            "endpoint", "m.endpoint",
            "transport", "m.transport",
            "enabled", "m.enabled",
            "healthStatus", "m.health_status",
            "lastProbeAt", "m.last_probe_at",
            "createdAt", "m.created_at",
            "updatedAt", "m.updated_at");
 
    private static final int MAX_OFFSET = 100_000;
    private static final String ENTITY_LABEL = "MCP 服务";
    private static final String OBJECT_TYPE = "mcp_server";
    
    private static final String SELECT_COLUMNS = """
            SELECT m.id, m.name, m.endpoint, m.transport, m.auth_enc, m.tools_json::text AS tools_json,
                   m.enabled, m.health_status, m.health_message, m.last_probe_at,
                   m.created_at, m.updated_at
            FROM tp_mcp_server m
            """;

    private final NamedParameterJdbcTemplate named;
    private final AuditLogService auditLogService;
    private final ConfigCipher configCipher;
    private final McpEndpointPolicy mcpEndpointPolicy;

    public McpServerServiceImpl(NamedParameterJdbcTemplate named, AuditLogService auditLogService,
                                ConfigCipher configCipher, McpEndpointPolicy mcpEndpointPolicy) {
        this.named = named;
        this.auditLogService = auditLogService;
        this.configCipher = configCipher;
        this.mcpEndpointPolicy = mcpEndpointPolicy;
    }

    @Override
    @Transactional
    public McpServerInfo create(Long operatorId, McpServerCreateInput input) {
        if (input == null) {
            throw new ServiceException("MCP 服务内容不能为空，请在「MCP 服务」页点「注册 MCP 服务」填写名称与端点后提交", BizErrorCode.VALIDATION_ERROR);
        }
        String name = validateName(input.name());
        String endpoint = mcpEndpointPolicy.validate(input.endpoint(), "端点");
        int transport = validateTransport(input.transport()).code();
        String authEnc = encryptAuth(input.auth());
        checkNameUnique(name, null);
        Long id;
        try {
            id = named.queryForObject("""
                    INSERT INTO tp_mcp_server (name, endpoint, transport, auth_enc)
                    VALUES (:name, :endpoint, :transport, :authEnc)
                    RETURNING id
                    """, new MapSqlParameterSource()
                    .addValue("name", name)
                    .addValue("endpoint", endpoint)
                    .addValue("transport", transport)
                    .addValue("authEnc", authEnc), Long.class);
        } catch (DuplicateKeyException e) {
            throw conflictName(name);
        }
        McpServerInfo created = get(id);
        recordAudit(operatorId, id, "create", null, created);
        return created;
    }

    @Override
    public McpServerInfo get(Long id) {
        List<McpServerInfo> rows = queryInfo("WHERE m.id = :id", new MapSqlParameterSource("id", id));
        if (rows.isEmpty()) {
            throw new ServiceException("MCP 服务不存在（id=" + id + "），请到「MCP 服务」页列表按名称搜索确认该服务是否已删除后重试", BizErrorCode.NOT_FOUND);
        }
        return rows.get(0);
    }

    @Override
    public PageResult<McpServerInfo> list(McpServerQuery query) {
        int safePage = Math.max(query.page(), 1);
        int safeSize = PageQueryPolicy.enforceSize(query.size());
        long offset = PageQueryPolicy.clampOffset(safePage, safeSize, MAX_OFFSET);
        String sortColumn = SortColumnPolicy.require(query.sortBy(), SORT_COLUMNS, ENTITY_LABEL);
        if (sortColumn == null) {
            sortColumn = "m.created_at";
        }
        String sortDir = "asc".equalsIgnoreCase(query.sortDir()) ? "ASC" : "DESC";

        MapSqlParameterSource params = new MapSqlParameterSource();
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        if (query.keyword() != null && !query.keyword().isBlank()) {
            where.append(" AND (m.name ILIKE :keyword OR m.endpoint ILIKE :keyword)");
            params.addValue("keyword", "%" + query.keyword().trim() + "%");
        }
        if (query.enabled() != null) {
            where.append(" AND m.enabled = :enabled");
            params.addValue("enabled", query.enabled());
        }
        if (query.health() != null) {
            where.append(" AND m.health_status = :health");
            params.addValue("health", query.health());
        }
        Long total = named.queryForObject(
                "SELECT count(*) FROM tp_mcp_server m" + where, params, Long.class);
        params.addValue("size", safeSize).addValue("offset", offset);
        List<McpServerInfo> list = queryInfo(where + " ORDER BY " + sortColumn + " " + sortDir
                + ", m.id DESC LIMIT :size OFFSET :offset", params);
        return new PageResult<>(list, total != null ? total : 0L);
    }

    @Override
    @Transactional
    public McpServerInfo update(Long operatorId, Long id, McpServerUpdateInput input) {
        if (input == null) {
            throw new ServiceException("MCP 服务编辑内容不能为空，请在「MCP 服务」页点该行「编辑」，在「编辑 MCP 服务」弹窗修改后提交", BizErrorCode.VALIDATION_ERROR);
        }
        McpServerRow current = queryRow(id);
        String name = input.name() == null ? current.getName() : validateName(input.name());
        String endpoint = input.endpoint() == null
                ? current.getEndpoint() : mcpEndpointPolicy.validate(input.endpoint(), "端点");
        int transport = input.transport() == null
                ? current.getTransport() : validateTransport(input.transport()).code();
        String authEnc = encryptAuth(input.auth());
        if (authEnc == null) {
            authEnc = current.getAuthEnc();
        }
        if (!name.equals(current.getName())) {
            checkNameUniqueExcept(name, id);
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("name", name)
                .addValue("endpoint", endpoint)
                .addValue("transport", transport)
                .addValue("authEnc", authEnc);
        try {
            named.update("""
                    UPDATE tp_mcp_server SET name = :name, endpoint = :endpoint,
                        transport = :transport, auth_enc = :authEnc, updated_at = now()
                    WHERE id = :id
                    """, params);
        } catch (DuplicateKeyException e) {
            throw conflictName(name);
        }
        McpServerInfo after = get(id);
        recordAudit(operatorId, id, "update",
                Map.of("name", current.getName(), "endpoint", current.getEndpoint(),
                        "transport", current.getTransport()), after);
        return after;
    }

    @Override
    @Transactional
    public McpServerInfo toggle(Long operatorId, Long id, int enabled) {
        if (enabled != 0 && enabled != 1) {
            throw new ServiceException("启停值 " + enabled + " 非法（1=启用 0=停用），请在「MCP 服务」页该行「启用」开关切换",
                    BizErrorCode.VALIDATION_ERROR);
        }
        McpServerRow current = queryRow(id);
        named.update("UPDATE tp_mcp_server SET enabled = :enabled, updated_at = now() WHERE id = :id",
                new MapSqlParameterSource("id", id).addValue("enabled", enabled));
        McpServerInfo after = get(id);
        recordAudit(operatorId, id, "toggle",
                Map.of("enabled", current.getEnabled()), after);
        return after;
    }

    @Override
    @Transactional
    public McpServerInfo updateTools(Long operatorId, Long id, McpToolSelectionInput input) {
        if (input == null || input.toolNames() == null) {
            throw new ServiceException("工具勾选清单不能为空（要全部收回权限请提交空清单），请在该行「工具勾选」弹窗勾选后点「保存勾选」",
                    BizErrorCode.VALIDATION_ERROR);
        }
        McpServerRow current = queryRow(id);
        if (McpToolSnapshot.parse(current.getToolsJson()) == null) {
            throw new ServiceException("该 MCP 服务尚未获取工具清单，请先「连接测试」成功后再配置工具勾选",
                    BizErrorCode.VALIDATION_ERROR);
        }
        Set<String> selected = new LinkedHashSet<>();
        for (String raw : input.toolNames()) {
            if (raw == null || raw.isBlank()) {
                throw new ServiceException("勾选清单含空工具名，请在该行「工具勾选」弹窗重新勾选后点「保存勾选」", BizErrorCode.VALIDATION_ERROR);
            }
            selected.add(raw.trim());
        }
        JsonNode snapshot = McpToolSnapshot.parse(current.getToolsJson());
        if (snapshot == null) {
            throw new ServiceException("工具快照格式异常（DB 直改等），请重新「连接测试」刷新快照后再勾选",
                    BizErrorCode.VALIDATION_ERROR);
        }
        Set<String> snapshotNames = new LinkedHashSet<>();
        snapshot.forEach(node -> snapshotNames.add(node.path("name").asText("")));
        List<String> unknown = selected.stream().filter(n -> !snapshotNames.contains(n)).toList();
        if (!unknown.isEmpty()) {
            throw new ServiceException("以下工具不在连接测试快照中，无法勾选：" + String.join("、", unknown)
                    + "（服务端工具清单变更后请重新「连接测试」刷新快照）", BizErrorCode.VALIDATION_ERROR);
        }
        for (JsonNode node : snapshot) {
            ((ObjectNode) node).put("enabled", selected.contains(node.path("name").asText("")));
        }
        named.update("UPDATE tp_mcp_server SET tools_json = :snapshot::jsonb, updated_at = now() "
                        + "WHERE id = :id",
                new MapSqlParameterSource("id", id).addValue("snapshot", snapshot.toString()));
        McpServerInfo after = get(id);
        recordAudit(operatorId, id, "tools",
                Map.of("enabledTools", McpToolSnapshot.enabledNames(current.getToolsJson())), after);
        return after;
    }

    @Override
    @Transactional
    public void delete(Long operatorId, Long id) {
        McpServerRow current = queryRow(id);
        int referencedBy = countToolReferences(id);
        if (referencedBy > 0) {
            throw new ServiceException("MCP 服务「" + current.getName() + "」已被 " + referencedBy
                    + " 条工具调用留痕引用，不能删除；请先到「MCP 服务」页用该行「启用」开关停用该服务（调用留痕为审计证据长期保留，不可解除）",
                    BizErrorCode.RESOURCE_CONFLICT);
        }
        named.update("DELETE FROM tp_mcp_server WHERE id = :id", new MapSqlParameterSource("id", id));
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("id", current.getId());
        before.put("name", current.getName());
        before.put("endpoint", current.getEndpoint());
        auditLogService.record(new AuditLogInput(operatorId, null, OBJECT_TYPE,
                String.valueOf(id), "delete", before, Map.of("deleted", true)));
    }

    
    private int countToolReferences(Long serverId) {
        Integer n = named.queryForObject(
                "SELECT count(*) FROM tp_tool_invocation WHERE server_id = :id",
                new MapSqlParameterSource("id", serverId), Integer.class);
        return n == null ? 0 : n;
    }

    // ------------------------------------------------------------------
    // ------------------------------------------------------------------

    private String validateName(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            throw new ServiceException("MCP 服务名称不能为空，请在「注册 MCP 服务」或「编辑 MCP 服务」弹窗填写「名称」", BizErrorCode.VALIDATION_ERROR);
        }
        String name = raw.trim();
        if (name.length() > NAME_MAX) {
            throw new ServiceException("MCP 服务名称最长 " + NAME_MAX + " 字（当前 "
                    + name.length() + " 字），请缩短「名称」后重新提交", BizErrorCode.VALIDATION_ERROR);
        }
        return name;
    }

    private McpTransport validateTransport(Integer raw) {
        if (raw == null) {
            throw new ServiceException("MCP 传输形态不能为空（可选：" + McpTransport.allLabels() + "），请在弹窗「传输形态」下拉选择",
                    BizErrorCode.VALIDATION_ERROR);
        }
        McpTransport transport = McpTransport.of(raw);
        if (transport == null) {
            throw new ServiceException("MCP 传输形态 " + raw + " 不在合法枚举（可选："
                    + McpTransport.allLabels() + "），请改为「传输形态」下拉的可选值之一", BizErrorCode.VALIDATION_ERROR);
        }
        return transport;
    }

    
    private String encryptAuth(String plainAuth) {
        if (plainAuth == null || plainAuth.isBlank()) {
            return null;
        }
        if (plainAuth.getBytes(StandardCharsets.UTF_8).length > AUTH_MAX_BYTES) {
            throw new ServiceException("MCP 鉴权凭证超过 4KB 上限，请在「鉴权凭证」改填单行凭证文本（粘贴整个文件内容会导致超限）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return configCipher.encrypt(plainAuth.trim());
    }

    private void checkNameUnique(String name, Long excludeId) {
        checkNameUniqueExcept(name, excludeId);
    }

    private void checkNameUniqueExcept(String name, Long excludeId) {
        String where = "name = :name";
        MapSqlParameterSource params = new MapSqlParameterSource("name", name);
        if (excludeId != null) {
            where += " AND id <> :excludeId";
            params.addValue("excludeId", excludeId);
        }
        Integer n = named.queryForObject(
                "SELECT count(*) FROM tp_mcp_server WHERE " + where, params, Integer.class);
        if (n != null && n > 0) {
            throw conflictName(name);
        }
    }

    private ServiceException conflictName(String name) {
        return new ServiceException("MCP 服务名称「" + name + "」已存在（全局注册面不允许重名），请改用其他名称，或到「MCP 服务」页搜索该名称确认后直接编辑已有服务",
                BizErrorCode.RESOURCE_CONFLICT);
    }

    // ------------------------------------------------------------------
    // ------------------------------------------------------------------

    
    private McpServerRow queryRow(Long id) {
        List<McpServerRow> rows = named.query(SELECT_COLUMNS + " WHERE m.id = :id",
                new MapSqlParameterSource("id", id), new BeanPropertyRowMapper<>(McpServerRow.class));
        if (rows.isEmpty()) {
            throw new ServiceException("MCP 服务不存在（id=" + id + "），请到「MCP 服务」页列表按名称搜索确认该服务是否已删除后重试", BizErrorCode.NOT_FOUND);
        }
        return rows.get(0);
    }

    
    private List<McpServerInfo> queryInfo(String whereClause, MapSqlParameterSource params) {
        return named.query(SELECT_COLUMNS + " " + whereClause, params,
                        new BeanPropertyRowMapper<>(McpServerRow.class))
                .stream().map(McpServerRow::toInfo).toList();
    }

 
    private void recordAudit(Long operatorId, Long id, String action,
                             Map<String, Object> before, McpServerInfo after) {
        Map<String, Object> afterMap = new LinkedHashMap<>();
        afterMap.put("id", after.id());
        afterMap.put("name", after.name());
        afterMap.put("endpoint", after.endpoint());
        afterMap.put("transport", after.transport());
        afterMap.put("transportLabel", after.transportLabel());
        afterMap.put("auth", after.authMasked() == null ? "none" : ConfigCipher.MASK);
        afterMap.put("enabled", after.enabled());
        afterMap.put("healthStatus", after.healthStatus());
        auditLogService.record(new AuditLogInput(operatorId, null, OBJECT_TYPE,
                String.valueOf(id), action, before, afterMap));
    }
}
