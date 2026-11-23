package com.sixbean.testpike.core.mcp.domain.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.audit.api.dto.AuditLogInput;
import com.sixbean.testpike.core.audit.domain.service.AuditLogService;
import com.sixbean.testpike.core.config.domain.component.ConfigCipher;
import com.sixbean.testpike.core.mcp.api.dto.McpProbeResult;
import com.sixbean.testpike.core.mcp.api.dto.McpServerInfo;
import com.sixbean.testpike.core.mcp.domain.component.McpEndpointPolicy;
import com.sixbean.testpike.core.mcp.domain.component.McpHandshakeClient;
import com.sixbean.testpike.core.mcp.domain.component.McpHandshakeOutcome;
import com.sixbean.testpike.core.mcp.domain.component.McpServerHealth;
import com.sixbean.testpike.core.mcp.domain.component.McpToolSnapshot;
import com.sixbean.testpike.core.mcp.domain.service.McpConnectivityService;
import com.sixbean.testpike.core.mcp.domain.service.McpServerService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;


@Service
public class McpConnectivityServiceImpl implements McpConnectivityService {

 
    private static final int MESSAGE_MAX = 500;
    private static final String OBJECT_TYPE = "mcp_server";

    private final NamedParameterJdbcTemplate named;
    private final McpServerService mcpServerService;
    private final McpHandshakeClient mcpHandshakeClient;
    private final McpEndpointPolicy mcpEndpointPolicy;
    private final ConfigCipher configCipher;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public McpConnectivityServiceImpl(NamedParameterJdbcTemplate named,
                                      McpServerService mcpServerService,
                                      McpHandshakeClient mcpHandshakeClient,
                                      McpEndpointPolicy mcpEndpointPolicy,
                                      ConfigCipher configCipher,
                                      AuditLogService auditLogService) {
        this.named = named;
        this.mcpServerService = mcpServerService;
        this.mcpHandshakeClient = mcpHandshakeClient;
        this.mcpEndpointPolicy = mcpEndpointPolicy;
        this.configCipher = configCipher;
        this.auditLogService = auditLogService;
    }

    @Override
    public McpProbeResult test(Long operatorId, Long id) {
        McpServerInfo before = mcpServerService.get(id);
        String endpoint = mcpEndpointPolicy.validate(before.endpoint(), "端点");
        String authPlain = decryptAuthQuietly(id);

        McpHandshakeOutcome outcome = mcpHandshakeClient.probe(endpoint, authPlain);

        if (outcome.success()) {
            String snapshot = snapshotJson(outcome, before.toolsJson());
            named.update("UPDATE tp_mcp_server SET tools_json = :snapshot::jsonb, "
                            + "health_status = :healthy, health_message = :message, "
                            + "last_probe_at = now(), updated_at = now() WHERE id = :id",
                    new MapSqlParameterSource()
                            .addValue("snapshot", snapshot)
                            .addValue("healthy", McpServerHealth.HEALTHY.code())
                            .addValue("message", clip(outcome.message()))
                            .addValue("id", id));
        } else {
            named.update("UPDATE tp_mcp_server SET health_status = :failed, "
                            + "health_message = :message, last_probe_at = now(), "
                            + "updated_at = now() WHERE id = :id",
                    new MapSqlParameterSource()
                            .addValue("failed", McpServerHealth.FAILED.code())
                            .addValue("message", clip(outcome.category().label() + "："
                                    + outcome.message()))
                            .addValue("id", id));
        }
        McpServerInfo after = mcpServerService.get(id);
        recordAudit(operatorId, id, before, after, outcome);
        return new McpProbeResult(outcome.success(), outcome.category().key(),
                outcome.message(), after);
    }

    
    private String decryptAuthQuietly(Long id) {
        String enc = named.queryForObject(
                "SELECT auth_enc FROM tp_mcp_server WHERE id = :id",
                new MapSqlParameterSource("id", id), String.class);
        if (enc == null || enc.isBlank()) {
            return null;
        }
        try {
            return configCipher.decrypt(enc);
        } catch (ServiceException e) {
            throw new ServiceException("MCP 鉴权凭证解密失败：请打开「编辑 MCP 服务」弹窗，在「鉴权凭证」字段重新填写并保存，再点击「连接测试」验证",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

 /** tools/list snapshot: [{name,description,inputSchemaDigest,enabled:false(,annotations)}]
  *  (default-reject for brand-new tools; previous enabled selections are inherited by name —
  *  a re-probe must never wash the management whitelist back to all-rejected). */
    private String snapshotJson(McpHandshakeOutcome outcome, String previousToolsJson) {
        ArrayNode arr = objectMapper.createArrayNode();
        outcome.tools().forEach(tool -> {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("name", String.valueOf(tool.get("name")));
            node.put("description", String.valueOf(tool.getOrDefault("description", "")));
            node.put("inputSchemaDigest", String.valueOf(tool.get("inputSchemaDigest")));
            node.put("enabled", false);
            // Persist the standard tool annotations carried by the handshake so the
            // read-only whitelist guard can tell them apart later (absent = not read-only).
            Object annotations = tool.get("annotations");
            if (annotations instanceof JsonNode jsonAnnotations && jsonAnnotations.isObject()) {
                node.set("annotations", jsonAnnotations);
            }
            arr.add(node);
        });
        McpToolSnapshot.inheritEnabled(arr, previousToolsJson);
        return arr.toString();
    }

    private static String clip(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= MESSAGE_MAX ? message : message.substring(0, MESSAGE_MAX);
    }

    
    private void recordAudit(Long operatorId, Long id, McpServerInfo before,
                             McpServerInfo after, McpHandshakeOutcome outcome) {
        Map<String, Object> beforeMap = new LinkedHashMap<>();
        beforeMap.put("healthStatus", before.healthStatus());
        Map<String, Object> afterMap = new LinkedHashMap<>();
        afterMap.put("id", after.id());
        afterMap.put("healthStatus", after.healthStatus());
        afterMap.put("healthLabel", after.healthLabel());
        afterMap.put("probeCategory", outcome.category().key());
        afterMap.put("probeMessage", clip(outcome.message()));
        if (outcome.success()) {
            afterMap.put("toolCount", outcome.tools().size());
        }
        auditLogService.record(new AuditLogInput(operatorId, null, OBJECT_TYPE,
                String.valueOf(id), "test", beforeMap, afterMap));
    }
}
