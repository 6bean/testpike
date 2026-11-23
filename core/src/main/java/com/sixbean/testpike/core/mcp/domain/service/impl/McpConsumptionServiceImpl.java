package com.sixbean.testpike.core.mcp.domain.service.impl;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.common.domain.component.DigestHex;
import com.sixbean.testpike.core.config.domain.component.ConfigCipher;
import com.sixbean.testpike.core.mcp.api.dto.McpAssemblyServer;
import com.sixbean.testpike.core.mcp.api.dto.McpInvocationInput;
import com.sixbean.testpike.core.mcp.domain.component.McpToolSnapshot;
import com.sixbean.testpike.core.mcp.domain.component.ProjectMcpAuthView;
import com.sixbean.testpike.core.mcp.domain.service.McpConsumptionService;
import com.sixbean.testpike.core.mcp.infrastructure.dataobject.McpServerRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;


@Service
public class McpConsumptionServiceImpl implements McpConsumptionService {

    private static final Logger log = LoggerFactory.getLogger(McpConsumptionServiceImpl.class);

 
    private static final Set<String> CALLERS = Set.of("mcp", "ai", "user");
 
    private static final int TOOL_NAME_MAX = 100;
 
    private static final int ERROR_MAX = 500;

    private final NamedParameterJdbcTemplate named;
    private final ConfigCipher configCipher;

    public McpConsumptionServiceImpl(NamedParameterJdbcTemplate named, ConfigCipher configCipher) {
        this.named = named;
        this.configCipher = configCipher;
    }

    @Override
    public List<McpAssemblyServer> getAssemblyServers() {
        return queryAssemblyServers();
    }

    @Override
    public List<McpAssemblyServer> getAssemblyServers(Long projectId) {
        if (projectId == null) {
            return queryAssemblyServers();
        }
        List<McpAssemblyServer> all = queryAssemblyServers();
        ProjectMcpAuthView config;
        try {
            config = loadProjectAuth(projectId);
        } catch (Exception e) {
            log.warn("项目 MCP 继承配置读取异常，回退全局装配（project_id={}）：{}", projectId, e.toString());
            return all;
        }
        if (config == null) {
            return all;
        }
        if (config.enabled() == 0) {
            return List.of();
        }
        List<String> templateTools = McpToolSnapshot.stringList(config.templateToolsJson());
        if (templateTools.isEmpty()) {
            log.warn("项目 {} 继承模板(id={}) 工具清单为空或格式异常，回退全局装配",
                    projectId, config.templateId());
            return all;
        }
        Set<String> allow = new java.util.HashSet<>(templateTools);
        return all.stream()
                .map(s -> new McpAssemblyServer(s.serverId(), s.name(), s.endpoint(),
                        s.transport(), s.auth(),
                        s.toolNames().stream().filter(allow::contains).toList()))
                .toList();
    }

    
    private ProjectMcpAuthView loadProjectAuth(Long projectId) {
        List<ProjectMcpAuthView> rows = named.query("""
                SELECT a.template_id, a.enabled, t.tools::text AS template_tools_json
                FROM tp_project_mcp_auth a
                LEFT JOIN tp_mcp_auth_template t ON t.id = a.template_id
                WHERE a.project_id = :projectId
                """, new MapSqlParameterSource("projectId", projectId),
                (rs, i) -> new ProjectMcpAuthView(rs.getLong("template_id"), rs.getInt("enabled"),
                        rs.getString("template_tools_json")));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private List<McpAssemblyServer> queryAssemblyServers() {
        List<McpServerRow> rows = named.query(
                "SELECT m.id, m.name, m.endpoint, m.transport, m.auth_enc, m.tools_json::text AS tools_json"
                        + " FROM tp_mcp_server m WHERE m.enabled = 1 ORDER BY m.id ASC",
                new MapSqlParameterSource(),
                new BeanPropertyRowMapper<>(McpServerRow.class));
        return rows.stream()
                .map(r -> new McpAssemblyServer(r.getId(), r.getName(), r.getEndpoint(),
                        r.getTransport() == null ? 0 : r.getTransport(),
                        decryptAuth(r.getAuthEnc()),
                        enabledToolNames(r.getToolsJson())))
                .toList();
    }

    @Override
    public int recordInvocations(List<McpInvocationInput> invocations) {
        if (invocations == null || invocations.isEmpty()) {
            return 0;
        }
        for (McpInvocationInput inv : invocations) {
            validateShape(inv);
        }
        int written = 0;
        for (McpInvocationInput inv : invocations) {
            written += named.update("""
                    INSERT INTO tp_tool_invocation (caller, actor_id, task_id, tool_id, server_id,
                                                   tool_name, success, duration_ms, error, digest_hash,
                                                   masked, api_token_id)
                    VALUES (:caller, :actorId, :taskId, :toolId, :serverId,
                            :toolName, :success, :durationMs, :error, :digestHash,
                            :masked, :apiTokenId)
                    """, new MapSqlParameterSource()
                    .addValue("caller", inv.caller().trim())
                    .addValue("actorId", inv.actorId())
                    .addValue("taskId", inv.taskId())
                    .addValue("toolId", inv.toolId())
                    .addValue("serverId", inv.serverId())
                    .addValue("toolName", inv.toolName().trim())
                    .addValue("success", inv.success())
                    .addValue("durationMs", inv.durationMs())
                    .addValue("error", clip(inv.error()))
                    .addValue("digestHash", inv.digest() == null ? null : DigestHex.sha256(inv.digest()))
                    .addValue("masked", inv.masked())
                    .addValue("apiTokenId", inv.apiTokenId()));
        }
        return written;
    }

    
    private void validateShape(McpInvocationInput inv) {
        if (inv == null) {
            throw new ServiceException("工具调用留痕记录不能为空：请上报一条完整的留痕对象后重新提交（示例：tool_name=create_case、caller=mcp、success=1）", BizErrorCode.VALIDATION_ERROR);
        }
        if (inv.caller() == null || !CALLERS.contains(inv.caller().trim())) {
            throw new ServiceException("工具调用留痕来源非法（caller=" + inv.caller() + "）：请把 caller 改为 mcp（外部调用）、ai（生成链）或 user（用户执行）后重新上报（正确写法：caller=mcp）", BizErrorCode.VALIDATION_ERROR);
        }
        if (inv.success() == null || (inv.success() != 0 && inv.success() != 1)) {
            throw new ServiceException("工具调用留痕成功标记非法（当前 success=" + inv.success() + "）：请把 success 改成 0（失败）或 1（成功）后重新提交（正确写法：success=1）", BizErrorCode.VALIDATION_ERROR);
        }
        if (inv.toolName() == null || inv.toolName().isBlank()) {
            throw new ServiceException("工具调用留痕缺少工具名：请在参数 tool_name 填写实际调用的工具名后重新提交（示例：tool_name=create_case）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (inv.toolName().trim().length() > TOOL_NAME_MAX) {
            throw new ServiceException("工具名称最长 " + TOOL_NAME_MAX + " 字（当前 tool_name 为 "
                    + inv.toolName().trim().length() + " 字）：请缩短 tool_name 到上限以内后重新提交（正确写法：只保留工具名，去掉描述句）", BizErrorCode.VALIDATION_ERROR);
        }
        if (inv.serverId() == null && inv.toolId() == null
                && !"mcp".equals(inv.caller() == null ? null : inv.caller().trim())) {
            throw new ServiceException("工具调用留痕必须携带 server_id（MCP 直调）或 tool_id"
                    + "（HTTP/执行器工具）至少其一：server_id 可在「MCP 服务」页列表中查看，tool_id 请填写系统内工具的数字 ID", BizErrorCode.VALIDATION_ERROR);
        }
    }

    
    private String decryptAuth(String authEnc) {
        if (authEnc == null || authEnc.isBlank()) {
            return null;
        }
        return configCipher.decrypt(authEnc);
    }

 
    private List<String> enabledToolNames(String toolsJson) {
        return McpToolSnapshot.enabledNames(toolsJson);
    }

 
    private static String clip(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= ERROR_MAX ? text : text.substring(0, ERROR_MAX);
    }
}
