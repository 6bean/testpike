package com.sixbean.testpike.core.mcp.domain.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.mcp.McpOutboundDesensitizeGate;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.sixbean.testpike.core.config.domain.component.AiMcpSyncKeyRanges;
import com.sixbean.testpike.core.config.domain.component.ConfigCipher;
import com.sixbean.testpike.core.config.domain.component.SystemConfigReader;
import com.sixbean.testpike.core.sched.domain.component.JobRunner;
import com.sixbean.testpike.core.mcp.api.dto.McpKnowledgeSyncInfo;
import com.sixbean.testpike.core.mcp.api.dto.McpKnowledgeSyncLandingResult;
import com.sixbean.testpike.core.mcp.api.dto.McpKnowledgeSyncRoundResult;
import com.sixbean.testpike.core.mcp.api.dto.McpKnowledgeSyncSaveRequest;
import com.sixbean.testpike.core.mcp.api.dto.McpKnowledgeSyncScanResult;
import com.sixbean.testpike.core.mcp.api.dto.McpProbeResult;
import com.sixbean.testpike.core.mcp.domain.component.McpHandshakeClient;
import com.sixbean.testpike.core.mcp.domain.component.McpToolCallOutcome;
import com.sixbean.testpike.core.mcp.domain.component.McpToolSnapshot;
import com.sixbean.testpike.core.mcp.domain.service.McpConnectivityService;
import com.sixbean.testpike.core.mcp.domain.service.McpKnowledgeSyncService;
import com.sixbean.testpike.core.mcp.infrastructure.dataobject.McpKnowledgeSyncRow;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * tp_mcp_knowledge_sync registration service .
 *
 * <p>Guard chain for save/update, all fail-closed with human messages that
 * name the offending object: the server must exist; the picked tool must sit
 * in the server's connection-test snapshot as enabled AND annotated read-only
 * ({@link McpToolSnapshot} single source — an automatic sync writes into the
 * knowledge ledger, so it must never point at a potentially mutating tool);
 * the interval must sit in 1..43200 minutes; the landing bucket must be 0
 * (global ledger) or an existing project; the name must be unique inside the
 * owning project. auto_activate defaults to off (quality gate: synced
 * entries wait for human batch activation).
 */
@Service
public class McpKnowledgeSyncServiceImpl implements McpKnowledgeSyncService {

    /** Every typed round failure leaves a WARN line so last_error text is reconstructable server-side. */
    private static final Logger log = LoggerFactory.getLogger(McpKnowledgeSyncServiceImpl.class);

    /** Guard range for syncIntervalMinutes (K8 style: service-layer single source). */
    private static final int MIN_INTERVAL_MINUTES = 1;
    private static final int MAX_INTERVAL_MINUTES = 43200;

    private static final RowMapper<McpKnowledgeSyncRow> ROW_MAPPER =
            BeanPropertyRowMapper.newInstance(McpKnowledgeSyncRow.class);

    /** Single tool-call budget for scheduled pulls (hot-tunable keys ride package 4). */
    private static final Duration SYNC_CALL_BUDGET = Duration.ofSeconds(30);

    private final NamedParameterJdbcTemplate named;
    private final ProjectService projectService;
    private final McpConnectivityService connectivityService;
    private final McpHandshakeClient handshakeClient;
    private final ConfigCipher configCipher;
    private final SystemConfigReader configReader;
    private final JobRunner jobRunner;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public McpKnowledgeSyncServiceImpl(NamedParameterJdbcTemplate named, ProjectService projectService,
                                       McpConnectivityService connectivityService,
                                       McpHandshakeClient handshakeClient,
                                       ConfigCipher configCipher,
                                       SystemConfigReader configReader,
                                       JobRunner jobRunner) {
        this.named = named;
        this.projectService = projectService;
        this.connectivityService = connectivityService;
        this.handshakeClient = handshakeClient;
        this.configCipher = configCipher;
        this.configReader = configReader;
        this.jobRunner = jobRunner;
    }

    @Override
    @Transactional
    public McpKnowledgeSyncInfo save(long operatorId, McpKnowledgeSyncSaveRequest input) {
        validate(input);
        String mappingJson = writeMapping(input.paramMapping());
        Long id;
        try {
            id = named.queryForObject("""
                    INSERT INTO tp_mcp_knowledge_sync (project_id, server_id, name, tool_name,
                        param_mapping, sync_interval_minutes, target_project_id, target_pack_id,
                        auto_activate, enabled, created_by)
                    VALUES (:projectId, :serverId, :name, :toolName,
                        :paramMapping::jsonb, :interval, :targetProjectId, :targetPackId,
                        :autoActivate, TRUE, :operatorId)
                    RETURNING id
                    """, new MapSqlParameterSource()
                    .addValue("projectId", input.projectId())
                    .addValue("serverId", input.serverId())
                    .addValue("name", input.name().trim())
                    .addValue("toolName", input.toolName())
                    .addValue("paramMapping", mappingJson)
                    .addValue("interval", resolveInterval(input.syncIntervalMinutes()))
                    .addValue("targetProjectId", input.targetProjectId())
                    .addValue("targetPackId", input.targetPackId())
                    .addValue("autoActivate", input.autoActivate() != null && input.autoActivate())
                    .addValue("operatorId", operatorId), Long.class);
        } catch (DuplicateKeyException e) {
            throw new ServiceException("项目内已存在同名同步订阅「" + input.name().trim()
                    + "」，请更换名称：在「新建同步订阅」的「订阅名称」输入框改用其它名称（项目内不可重复）",
                    BizErrorCode.RESOURCE_CONFLICT);
        }
        return detail(id);
    }

    @Override
    @Transactional
    public McpKnowledgeSyncInfo update(long operatorId, long id, McpKnowledgeSyncSaveRequest input) {
        detail(id);
        validate(input);
        String mappingJson = writeMapping(input.paramMapping());
        try {
            named.update("""
                    UPDATE tp_mcp_knowledge_sync
                       SET project_id = :projectId, server_id = :serverId, name = :name,
                           tool_name = :toolName, param_mapping = :paramMapping::jsonb,
                           sync_interval_minutes = :interval, target_project_id = :targetProjectId,
                           target_pack_id = :targetPackId, auto_activate = :autoActivate,
                           updated_at = now()
                     WHERE id = :id
                    """, new MapSqlParameterSource()
                    .addValue("projectId", input.projectId())
                    .addValue("serverId", input.serverId())
                    .addValue("name", input.name().trim())
                    .addValue("toolName", input.toolName())
                    .addValue("paramMapping", mappingJson)
                    .addValue("interval", resolveInterval(input.syncIntervalMinutes()))
                    .addValue("targetProjectId", input.targetProjectId())
                    .addValue("targetPackId", input.targetPackId())
                    .addValue("autoActivate", input.autoActivate() != null && input.autoActivate())
                    .addValue("id", id));
        } catch (DuplicateKeyException e) {
            throw new ServiceException("项目内已存在同名同步订阅「" + input.name().trim()
                    + "」，请更换名称：在「编辑同步订阅」的「订阅名称」输入框改用其它名称（项目内不可重复）",
                    BizErrorCode.RESOURCE_CONFLICT);
        }
        return detail(id);
    }

    @Override
    public void pause(long operatorId, long id) {
        flipEnabled(id, false);
    }

    @Override
    public void resume(long operatorId, long id) {
        flipEnabled(id, true);
    }

    @Override
    @Transactional
    public void delete(long operatorId, long id) {
        detail(id);
        named.update("DELETE FROM tp_mcp_knowledge_sync WHERE id = :id",
                new MapSqlParameterSource("id", id));
    }

    @Override
    public McpKnowledgeSyncInfo detail(long id) {
        List<McpKnowledgeSyncInfo> found = named.query("""
                SELECT s.*, m.name AS server_name
                  FROM tp_mcp_knowledge_sync s
                  LEFT JOIN tp_mcp_server m ON m.id = s.server_id
                 WHERE s.id = :id
                """, new MapSqlParameterSource("id", id), infoMapper());
        if (found.isEmpty()) {
            throw new ServiceException("同步订阅不存在（id=" + id
                    + "）：该订阅可能已被删除，请回到「MCP 服务」页该行「同步订阅」清单刷新确认",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return found.get(0);
    }

    @Override
    public PageResult<McpKnowledgeSyncInfo> list(Long projectId, Long serverId, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 200);
        int safePage = Math.max(page, 1);
        // Predicates join the shared WHERE clause only when bound. A parameter
        // used in a bare `IS NULL` test carries no type information when the
        // driver binds a null without an explicit type OID, and PostgreSQL then
        // fails Parse with "could not determine data type of parameter".
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (projectId != null) {
            where.append(" AND s.project_id = :projectId");
            params.addValue("projectId", projectId);
        }
        if (serverId != null) {
            where.append(" AND s.server_id = :serverId");
            params.addValue("serverId", serverId);
        }
        Long total = named.queryForObject(
                "SELECT count(*) FROM tp_mcp_knowledge_sync s" + where, params, Long.class);
        List<McpKnowledgeSyncInfo> rows = named.query(
                "SELECT s.*, m.name AS server_name"
                        + " FROM tp_mcp_knowledge_sync s"
                        + " LEFT JOIN tp_mcp_server m ON m.id = s.server_id"
                        + where
                        + " ORDER BY s.id DESC LIMIT :limit OFFSET :offset",
                params.addValue("limit", safeSize).addValue("offset", (safePage - 1) * safeSize),
                infoMapper());
        return new PageResult<>(rows, total == null ? 0L : total);
    }

    private void flipEnabled(long id, boolean enabled) {
        // Resuming restarts the consecutive-failure path from zero: the threshold
        // edge in markRound triggers exactly once per pause cycle (its comment
        // already promises this contract). A manual pause keeps the ledger counter.
        int updated = named.update("""
                UPDATE tp_mcp_knowledge_sync
                   SET enabled = :enabled,
                       consecutive_failures = CASE WHEN :enabled THEN 0 ELSE consecutive_failures END,
                       updated_at = now()
                 WHERE id = :id
                """, new MapSqlParameterSource()
                .addValue("enabled", enabled)
                .addValue("id", id));
        if (updated == 0) {
            throw new ServiceException("同步订阅不存在（id=" + id
                    + "）：该订阅可能已被删除，请在「MCP 服务」页「同步订阅」清单刷新后对仍在的订阅行重试「暂停订阅」或「恢复订阅」",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    /** Runs the full registration guard chain shared by save and update. */
    private void validate(McpKnowledgeSyncSaveRequest input) {
        if (input == null) {
            throw new ServiceException("同步订阅内容不能为空：请在「MCP 服务」页「同步订阅」抽屉点「新建订阅」填写表单后提交",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (input.name() == null || input.name().isBlank()) {
            throw new ServiceException("订阅名称不能为空：请在「新建同步订阅」的「订阅名称」输入框填写（项目内不可重复）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (input.projectId() == null || !projectService.exists(input.projectId())) {
            throw new ServiceException("订阅所属项目不存在（id=" + input.projectId()
                    + "）：请先在顶栏「选择项目」下拉选中项目再配置订阅，接口调用请传 projectId=数字项目ID",
                    BizErrorCode.VALIDATION_ERROR);
        }
        List<String> snapshots = named.queryForList(
                "SELECT tools_json::text FROM tp_mcp_server WHERE id = :id",
                new MapSqlParameterSource("id", input.serverId()), String.class);
        if (snapshots.isEmpty() || snapshots.get(0) == null) {
            throw new ServiceException("MCP 服务不存在（id=" + input.serverId()
                    + "）：服务可能已从清单移除，请回到「MCP 服务」列表确认后再配置，接口调用请传 serverId=数字服务ID",
                    BizErrorCode.VALIDATION_ERROR);
        }
        String toolsJson = snapshots.get(0);
        validateToolSnapshot(toolsJson, input.toolName());
        // null = "use the hot-updated ai.mcp.sync.interval-minutes default"; an explicit
        // value still has to sit inside the same range the config group guards.
        Integer explicitInterval = input.syncIntervalMinutes();
        if (explicitInterval != null
                && (explicitInterval < MIN_INTERVAL_MINUTES || explicitInterval > MAX_INTERVAL_MINUTES)) {
            throw new ServiceException("同步间隔必须在 " + MIN_INTERVAL_MINUTES + ".."
                    + MAX_INTERVAL_MINUTES + " 分钟之间（当前 " + explicitInterval + " 分钟）"
                    + "：请在「同步周期（分钟）」输入框改填该区间内的整数，留空则按默认周期"
                    + "（可在系统设置 ai.mcp.sync.interval-minutes 调整）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (input.targetProjectId() != 0L && !projectService.exists(input.targetProjectId())) {
            throw new ServiceException("知识入库目标项目不存在（id=" + input.targetProjectId()
                    + "），0 表示全局台账：请在「目标知识库」下拉重选，选「全局知识台账」即 targetProjectId=0",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    private void validateToolSnapshot(String toolsJson, String toolName) {
        if (toolName == null || toolName.isBlank()) {
            throw new ServiceException("请选择要同步的工具：在「新建同步订阅」的「同步工具」下拉选择，仅列出「工具勾选」中开放的只读工具",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (McpToolSnapshot.isEnabledReadOnly(toolsJson, toolName)) {
            return;
        }
        if (McpToolSnapshot.enabledNames(toolsJson).contains(toolName)) {
            throw new ServiceException("工具「" + toolName
                    + "」未标记为只读（read-only），自动同步只允许只读工具"
                    + "：请在「MCP 服务」页该服务的「工具勾选」中开放该工具，或改选其它只读工具",
                    BizErrorCode.VALIDATION_ERROR);
        }
        throw new ServiceException("工具「" + toolName + "」不在该服务快照的已启用工具清单中"
                + "：请先对该服务点「连接测试」刷新工具快照，再在「同步工具」下拉重选",
                BizErrorCode.VALIDATION_ERROR);
    }

    private String writeMapping(Map<String, Object> mapping) {
        if (mapping == null || mapping.isEmpty()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(mapping);
        } catch (Exception e) {
            throw new ServiceException("参数映射必须是合法的键值对象：请在「工具参数」区逐行填写「参数名」「参数值」，接口调用请传 paramMapping=JSON 对象",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    private RowMapper<McpKnowledgeSyncInfo> infoMapper() {
        return (rs, rowNum) -> mapRowWithServerName(rs, rowNum);
    }

    private McpKnowledgeSyncInfo mapRowWithServerName(java.sql.ResultSet rs, int rowNum) throws SQLException {
        McpKnowledgeSyncRow row = ROW_MAPPER.mapRow(rs, rowNum);
        return row.toInfo(rs.getString("server_name"));
    }

    // ------------------------------------------------------------------
    // Sync loop segment
    // ------------------------------------------------------------------

    @Override
    public McpKnowledgeSyncScanResult scanDue() {
        // K8 master switch (ai.mcp.sync.enabled): off short-circuits the whole round
        // into an empty ledger — no subscription is executed or marked.
        if (!configReader.getBoolean(AiMcpSyncKeyRanges.KEY_ENABLED, AiMcpSyncKeyRanges.DEFAULT_ENABLED)) {
            return new McpKnowledgeSyncScanResult(0, 0, 0, 0);
        }
        int cap = configReader.getInt(AiMcpSyncKeyRanges.KEY_MAX_ENTRIES_PER_ROUND,
                AiMcpSyncKeyRanges.DEFAULT_MAX_ENTRIES_PER_ROUND);
        List<McpKnowledgeSyncRow> due = named.query("""
                SELECT * FROM tp_mcp_knowledge_sync
                 WHERE enabled AND (last_sync_at IS NULL
                    OR last_sync_at <= now() - make_interval(mins => sync_interval_minutes))
                 ORDER BY id
                 LIMIT :cap
                """, new MapSqlParameterSource("cap", cap), ROW_MAPPER);
        int updated = 0;
        int unchanged = 0;
        int failed = 0;
        for (McpKnowledgeSyncRow sub : due) {
            long operatorId = sub.getCreatedBy() == null ? 1L : sub.getCreatedBy();
            // runOnce never throws for expected failures (probe/transport typing);
            // anything escaping here is still counted instead of killing the loop.
            String status;
            try {
                status = runOnce(operatorId, sub.getId()).status();
            } catch (RuntimeException e) {
                status = "failed";
                log.warn("MCP knowledge sync scan round threw an untyped failure: subscription={}",
                        sub.getId(), e);
                markRound(sub.getId(), "failed", 0, 0, clip(e.getMessage()));
            }
            switch (status) {
                case "updated" -> updated++;
                case "unchanged" -> unchanged++;
                default -> failed++;
            }
        }
        return new McpKnowledgeSyncScanResult(due.size(), updated, unchanged, failed);
    }

    @Override
    public McpKnowledgeSyncRoundResult runOnce(long operatorId, long id) {
        McpKnowledgeSyncRow sub = loadRow(id);
        // DOWN gate: consume the existing connectivity probe (single source,
        // no second transport stack). A refusal (endpoint policy included) is
        // a typed round failure written to the ledger, never a thrown loop.
        String gateError = null;
        try {
            McpProbeResult probe = connectivityService.test(operatorId, sub.getServerId());
            if (!probe.success()) {
                gateError = probe.category() + ": " + probe.message();
            }
        } catch (ServiceException e) {
            gateError = e.getMessage();
        } catch (RuntimeException e) {
            gateError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }
        if (gateError != null) {
            String ledgerError = clip(gateError);
            log.warn("MCP knowledge sync round failed before transport: subscription={}, server={}, error={}",
                    id, sub.getServerId(), ledgerError);
            markRound(id, "failed", 0, 0, ledgerError);
            return new McpKnowledgeSyncRoundResult("failed", ledgerError, 0, 0);
        }
        List<String> endpoints = named.queryForList(
                "SELECT endpoint FROM tp_mcp_server WHERE id = :id",
                new MapSqlParameterSource("id", sub.getServerId()), String.class);
        String endpoint = endpoints.isEmpty() ? "" : endpoints.get(0);
        JsonNode arguments;
        try {
            String mapping = sub.getParamMapping();
            arguments = objectMapper.readTree(mapping == null || mapping.isBlank() ? "{}" : mapping);
        } catch (Exception e) {
            log.warn("MCP knowledge sync round failed on param mapping: subscription={}, server={}",
                    id, sub.getServerId(), e);
            markRound(id, "failed", 0, 0, "param mapping is not a JSON object");
            return new McpKnowledgeSyncRoundResult("failed", "参数映射不是合法的 JSON 对象", 0, 0);
        }
        // : everything leaving the platform toward an external MCP server
        // runs through the shared outbound gate (single source, fail-closed —
        // a masking failure refuses this round instead of sending raw data).
        McpOutboundDesensitizeGate outboundGate = McpOutboundDesensitizeGate.fromRawValue(
                configReader.getString(McpOutboundDesensitizeGate.ENFORCE_KEY, null));
        try {
            arguments = outboundGate.prepareNode(arguments, objectMapper);
        } catch (RuntimeException e) {
            log.warn("MCP knowledge sync round refused on outbound desensitization: subscription={}",
                    id, e);
            markRound(id, "failed", 0, 0, "outbound desensitization failed");
            return new McpKnowledgeSyncRoundResult("failed", "出站内容脱敏失败，本轮已拒绝外呼", 0, 0);
        }
        McpToolCallOutcome call = handshakeClient.callTool(endpoint, resolveAuthPlain(sub.getServerId()),
                sub.getToolName(), arguments, SYNC_CALL_BUDGET);
        if (!call.success()) {
            String err = clip(call.category() + ": " + call.message());
            log.warn("MCP knowledge sync round failed on tool call: subscription={}, server={}, tool={}, error={}",
                    id, sub.getServerId(), sub.getToolName(), err);
            markRound(id, "failed", 0, 0, err);
            return new McpKnowledgeSyncRoundResult("failed", err, 0, 0);
        }
        String text = call.text() == null ? "" : call.text();
        if (text.isBlank()) {
            // An empty tool answer is a no-op round, not a garbage ledger row.
            markRound(id, "unchanged", 0, 1, null);
            return new McpKnowledgeSyncRoundResult("unchanged", null, 0, 1);
        }
        McpKnowledgeSyncLandingResult landing = ingestPayload(id, text, endpoint);
        return new McpKnowledgeSyncRoundResult(landing.inserted() > 0 ? "updated" : "unchanged", null,
                landing.inserted(), landing.skipped());
    }

    @Override
    @Transactional
    public McpKnowledgeSyncLandingResult ingestPayload(long subscriptionId, String payload, String sourceUrl) {
        McpKnowledgeSyncRow sub = loadRow(subscriptionId);
        String sha = sha256Hex(payload);
        Integer topVersion = named.queryForObject("""
                SELECT COALESCE(MAX(version_no), 0) FROM tp_mcp_knowledge_sync_entry
                 WHERE subscription_id = :sid
                """, new MapSqlParameterSource("sid", subscriptionId), Integer.class);
        int head = topVersion == null ? 0 : topVersion;
        if (head > 0) {
            List<String> headShas = named.queryForList("""
                    SELECT payload_sha FROM tp_mcp_knowledge_sync_entry
                     WHERE subscription_id = :sid AND version_no = :v
                    """, new MapSqlParameterSource("sid", subscriptionId).addValue("v", head), String.class);
            if (!headShas.isEmpty() && sha.equals(headShas.get(0))) {
                // Fingerprint dedup: identical latest payload replay lands nothing.
                markRound(subscriptionId, "unchanged", 0, 1, null);
                return new McpKnowledgeSyncLandingResult(0, 1);
            }
        }
        int version = head + 1;
        // tp_knowledge_doc.source_ref is BIGINT (entity case ids) and the partial
        // unique key uq_kb_doc_entity(source_type, project_id, source_ref) allows
        // exactly one doc row per subscription: the ledger row is versioned in
        // place (content + title carry the revision marker, revision bumps) and
        // the lineage table keeps one row per landed version as the join face.
        // Pending-vs-active transitions on external revisions ride the re-apply
        // preview flow (package 3, aligned with the lineage design ruling).
        String title = clip(sub.getName() + " (v" + version + ")");
        Long docId;
        if (head == 0) {
            docId = named.queryForObject("""
                    INSERT INTO tp_knowledge_doc (project_id, pack_id, source_type, source_ref, title, content, status)
                    VALUES (:projectId, :packId, 'MCP_SYNC', :sourceRef, :title, :content, :status)
                    RETURNING id
                    """, new MapSqlParameterSource()
                    .addValue("projectId", sub.getTargetProjectId())
                    .addValue("packId", sub.getTargetPackId())
                    .addValue("sourceRef", subscriptionId)
                    .addValue("title", title)
                    .addValue("content", payload)
                    .addValue("status", Boolean.TRUE.equals(sub.getAutoActivate()) ? "ACTIVE" : "INACTIVE"),
                    Long.class);
        } else {
            List<Long> headDocs = named.queryForList("""
                    SELECT doc_id FROM tp_mcp_knowledge_sync_entry
                     WHERE subscription_id = :sid AND version_no = :v
                    """, new MapSqlParameterSource("sid", subscriptionId).addValue("v", head), Long.class);
            docId = headDocs.get(0);
            named.update("""
                    UPDATE tp_knowledge_doc
                       SET content = :content, title = :title, revision = revision + 1, updated_at = now()
                     WHERE id = :id
                    """, new MapSqlParameterSource()
                    .addValue("content", payload)
                    .addValue("title", title)
                    .addValue("id", docId));
        }
        named.update("""
                INSERT INTO tp_mcp_knowledge_sync_entry
                    (subscription_id, payload_sha, doc_id, version_no, source_url, state)
                VALUES (:sid, :sha, :docId, :version, :url, 'LANDED')
                """, new MapSqlParameterSource()
                .addValue("sid", subscriptionId)
                .addValue("sha", sha)
                .addValue("docId", docId)
                .addValue("version", version)
                .addValue("url", clip(sourceUrl)));
        markRound(subscriptionId, "updated", 1, 0, null);
        return new McpKnowledgeSyncLandingResult(1, 0);
    }

    private McpKnowledgeSyncRow loadRow(long id) {
        List<McpKnowledgeSyncRow> rows = named.query(
                "SELECT * FROM tp_mcp_knowledge_sync WHERE id = :id",
                new MapSqlParameterSource("id", id), ROW_MAPPER);
        if (rows.isEmpty()) {
            throw new ServiceException("同步订阅不存在（id=" + id
                    + "）：订阅可能已被删除，可在「MCP 服务」页「同步订阅」抽屉点「新建订阅」重新配置",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return rows.get(0);
    }

    /** Ledger round outcome; failed rounds bump consecutive_failures, success resets it (K2 alert source). */
    private void markRound(long id, String status, int newCount, int skipped, String error) {
        named.update("""
                UPDATE tp_mcp_knowledge_sync
                   SET last_status = :status, last_new_count = :newCount, last_skipped_count = :skipped,
                       last_error = :error, last_sync_at = now(),
                       consecutive_failures = CASE WHEN :status = 'failed'
                           THEN consecutive_failures + 1 ELSE 0 END,
                       updated_at = now()
                 WHERE id = :id
                """, new MapSqlParameterSource()
                .addValue("status", status)
                .addValue("newCount", newCount)
                .addValue("skipped", skipped)
                .addValue("error", error)
                .addValue("id", id));
        if (!"failed".equals(status)) {
            return;
        }
        Integer fails = named.queryForObject(
                "SELECT consecutive_failures FROM tp_mcp_knowledge_sync WHERE id = :id",
                new MapSqlParameterSource("id", id), Integer.class);
        int threshold = configReader.getInt(AiMcpSyncKeyRanges.KEY_FAILURE_THRESHOLD,
                AiMcpSyncKeyRanges.DEFAULT_FAILURE_THRESHOLD);
        // Edge trigger (== not >=): fires exactly once per pause cycle because a
        // resume resets the counter; >= would re-alert and re-pause every round.
        if (fails == null || fails != threshold) {
            return;
        }
        McpKnowledgeSyncRow sub = loadRow(id);
        String pauseNote = "连续 " + threshold + " 轮失败已自动暂停，可在订阅列表恢复"
                + "（阈值可在系统配置页调整：ai.mcp.sync.failure-threshold）";
        named.update("UPDATE tp_mcp_knowledge_sync SET enabled = FALSE, last_error = :note, updated_at = now()"
                        + " WHERE id = :id",
                new MapSqlParameterSource("note", clip(pauseNote)).addValue("id", id));
        jobRunner.alert("mcpKnowledgeSyncScan", new IllegalStateException(
                "MCP 知识库同步订阅「" + sub.getName() + "」连续失败 " + threshold
                        + " 轮已自动暂停；请检查该 MCP 服务的连通性与订阅参数后，在对应服务明细的同步订阅列表恢复"));
    }

    /** Cadence to persist when the request carries none: hot-updated key default (K8). */
    private int resolveInterval(Integer raw) {
        return raw == null
                ? configReader.getInt(AiMcpSyncKeyRanges.KEY_INTERVAL_MINUTES,
                        AiMcpSyncKeyRanges.DEFAULT_INTERVAL_MINUTES)
                : raw;
    }

    /** Server auth plaintext for the scheduled call; NULL column = no auth (cipher stays single-source). */
    private String resolveAuthPlain(long serverId) {
        String enc = named.queryForObject(
                "SELECT auth_enc FROM tp_mcp_server WHERE id = :id",
                new MapSqlParameterSource("id", serverId), String.class);
        return enc == null || enc.isBlank() ? null : configCipher.decrypt(enc);
    }

    private static String sha256Hex(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable on this JVM", e);
        }
    }

    private static String clip(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
}
