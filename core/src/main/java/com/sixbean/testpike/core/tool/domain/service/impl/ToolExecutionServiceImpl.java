package com.sixbean.testpike.core.tool.domain.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.mcp.McpOutboundDesensitizeGate;
import com.sixbean.testpike.core.config.domain.component.SystemConfigReader;
import com.sixbean.testpike.common.approval.ApprovalStateMachine;
import com.sixbean.testpike.common.approval.ApprovalStatus;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.genkit.GenKit;
import com.sixbean.testpike.core.audit.api.dto.AuditLogInput;
import com.sixbean.testpike.core.audit.domain.component.OperatorContext;
import com.sixbean.testpike.core.audit.domain.service.AuditLogService;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import com.sixbean.testpike.core.executor.api.dto.ExecTaskInfo;
import com.sixbean.testpike.core.executor.domain.service.ExecTaskService;
import com.sixbean.testpike.core.executor.domain.service.ExecutorService;
import com.sixbean.testpike.core.mcp.api.dto.McpAssemblyServer;
import com.sixbean.testpike.core.mcp.api.dto.McpInvocationInput;
import com.sixbean.testpike.core.mcp.domain.component.McpEndpointPolicy;
import com.sixbean.testpike.core.mcp.domain.component.McpHandshakeClient;
import com.sixbean.testpike.core.mcp.domain.component.McpToolCallOutcome;
import com.sixbean.testpike.core.mcp.domain.service.McpConsumptionService;
import com.sixbean.testpike.core.tool.api.dto.ToolConfirmTicket;
import com.sixbean.testpike.core.tool.api.dto.ToolInfo;
import com.sixbean.testpike.core.tool.api.dto.ToolTestRunResult;
import com.sixbean.testpike.core.tool.domain.component.CappedBody;
import com.sixbean.testpike.core.tool.domain.component.McpBridgeCall;
import com.sixbean.testpike.core.tool.domain.component.ToolAccessGate;
import com.sixbean.testpike.core.tool.domain.component.ToolConfirmTokenStore;
import com.sixbean.testpike.core.tool.domain.component.ToolExecKind;
import com.sixbean.testpike.core.tool.domain.component.ToolWriteFlag;
import com.sixbean.testpike.core.tool.domain.service.ToolExecutionService;
import com.sixbean.testpike.core.tool.domain.service.ToolService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


@Service
public class ToolExecutionServiceImpl implements ToolExecutionService {

    
    static final int MAX_OUTPUT_BYTES = 1024 * 1024;
    
    static final String TRUNCATED_SUFFIX = "…[响应超 1MB，已截断]";
    
    private static final int ERROR_MAX = 500;
    
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    
    static final String AUTH_REF_KEY_PREFIX = "tool.auth-ref.";
    private static final String AUDIT_ACTION = "test-run";
    
    private static final String TRACE_CALLER_AI = "ai";
    private static final String TRACE_CALLER_USER = "user";
    private static final String AUDIT_ACTION_INVOKE = "invoke";
    
    static final String AUDIT_ACTION_CONFIRM_ISSUE = "confirm-issue";
    
    static final int EXECUTOR_SANDBOX_MAX_MS = 30_000;
    
    private static final long EXECUTOR_POLL_INTERVAL_MS = 50;

    private final ToolService toolService;
    private final McpEndpointPolicy endpointPolicy;
    private final McpConsumptionService mcpConsumptionService;
    private final McpHandshakeClient mcpHandshakeClient;
    private final ConfigCacheHolder configCacheHolder;
    private final SystemConfigReader outboundConfigReader;
    private final AuditLogService auditLogService;
    private final ExecTaskService execTaskService;
    private final ExecutorService executorService;
    private final JdbcTemplate jdbcTemplate;
    private final ToolConfirmTokenStore confirmTokenStore;
    private final ToolAccessGate accessGate;
    /**
     * Same-instance dialing for this application's own pass-through doors: a stored url carries the
     * port of whichever instance seeded it, so the authority is corrected at dial time (never in the
     * registry row, where two stacks on one database would overwrite each other).
     */
    private final com.sixbean.testpike.core.apiaccess.domain.component.ApiAccessDoorAuthority doorAuthority;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public ToolExecutionServiceImpl(ToolService toolService,
                                    McpEndpointPolicy endpointPolicy,
                                    McpConsumptionService mcpConsumptionService,
                                    McpHandshakeClient mcpHandshakeClient,
                                    ConfigCacheHolder configCacheHolder,
                                    AuditLogService auditLogService,
                                    ExecTaskService execTaskService,
                                    ExecutorService executorService,
                                    JdbcTemplate jdbcTemplate,
                                    ToolConfirmTokenStore confirmTokenStore,
                                    ToolAccessGate accessGate,
                                    com.sixbean.testpike.core.apiaccess.domain.component.ApiAccessDoorAuthority doorAuthority) {
        this.toolService = toolService;
        this.endpointPolicy = endpointPolicy;
        this.mcpConsumptionService = mcpConsumptionService;
        this.mcpHandshakeClient = mcpHandshakeClient;
        this.configCacheHolder = configCacheHolder;
        this.outboundConfigReader = new SystemConfigReader(configCacheHolder);
        this.auditLogService = auditLogService;
        this.execTaskService = execTaskService;
        this.executorService = executorService;
        this.jdbcTemplate = jdbcTemplate;
        this.confirmTokenStore = confirmTokenStore;
        this.accessGate = accessGate;
        this.doorAuthority = doorAuthority;
    }

    @Override
    public ToolTestRunResult testRun(Long operatorId, Long toolId, String inputJson) {
        ToolInfo tool = toolService.get(toolId);
        return execute(operatorId, null, tool, inputJson, TRACE_CALLER_USER, AUDIT_ACTION);
    }

    
    @Override
    public ToolTestRunResult invoke(Long actorId, Long taskId, Long toolId, String inputJson,
                                    String confirmToken, Long assertedProjectId) {
        ToolInfo tool = toolService.get(toolId);
        requirePublished(tool);
        // AO batch stage 1: per-call execution-point re-check of the
        // five-way gate (project visibility + execution-class switch). Lands
        // BEFORE confirm-token consumption so a privilege refusal never burns
        // a one-time ticket; guarded refusal = zero trace, zero audit.
        accessGate.requireInvokeAllowed(tool.projectId(), actorId, tool.execKind(), tool.name());
        // AO batch stage 3: identity-tampering seam on the AI chain — a
        // model-supplied reserved key (projectId/operatorId/actorId/userId)
        // that contradicts the trusted fact is refused before the execution
        // path runs, but WITH trace + audit (refuse-and-record, deliberately
        // split from the zero-trace visibility guard above). It also lands
        // before confirm-token consumption so a tampered payload never burns a
        // one-time ticket. The trusted project fact falls back to the value
        // the server asserted on the invoke carrier for system-level rows
        // (project_id NULL, ) — the model cannot write that field, and
        // with no anchor anywhere every project claim stays refused.
        String tampered = accessGate.findIdentityTampering(inputJson, tool.projectId(),
                assertedProjectId, actorId);
        if (tampered != null) {
            ToolTestRunResult refusal = fail(tool, "identity", BizErrorCode.FORBIDDEN, tampered);
            Long refusalServerId = tool.execKind() != null
                    && ToolExecKind.of(tool.execKind()) == ToolExecKind.MCP_BRIDGE
                    ? mcpServerIdOf(tool) : null;
            recordTraceAndAudit(actorId, taskId, tool, inputJson, refusal, 0L,
                    refusalServerId, // identity refusal = zero outbound body, facet not applicable
                    null, TRACE_CALLER_AI, AUDIT_ACTION_INVOKE);
            return refusal;
        }
        if (ToolWriteFlag.resolve(tool.inputSchemaJson())) {
            if (confirmToken == null || confirmToken.isBlank()) {
                throw new ServiceException("工具「" + tool.name() + "」具有写语义（x-write），"
                        + "需人工确认后执行——请先在「工具管理」页签发确认票（confirm-token）再携票调用",
                        BizErrorCode.VALIDATION_ERROR);
            }
            confirmTokenStore.consume(confirmToken, tool.id());
        }
        return execute(actorId, taskId, tool, inputJson, TRACE_CALLER_AI, AUDIT_ACTION_INVOKE);
    }

    
    @Override
    public ToolConfirmTicket issueConfirmToken(Long operatorId, Long toolId) {
        ToolInfo tool = toolService.get(toolId);
        requirePublished(tool);
        if (!ToolWriteFlag.resolve(tool.inputSchemaJson())) {
            throw new ServiceException("工具「" + tool.name() + "」非写语义（无 x-write），"
                    + "无需确认票——请在「工具管理」页直接「发起试跑」或经 AI 链执行", BizErrorCode.VALIDATION_ERROR);
        }
        ToolConfirmTicket ticket = confirmTokenStore.issue(tool.id());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("id", tool.id());
        after.put("name", tool.name());
        after.put("expiresInSeconds", ticket.expiresInSeconds());
        auditLogService.record(new AuditLogInput(operatorId, tool.projectId(), "tool",
                String.valueOf(tool.id()), AUDIT_ACTION_CONFIRM_ISSUE, null, after));
        return ticket;
    }

    
    private void requirePublished(ToolInfo tool) {
        if (!ApprovalStateMachine.isPublished(tool.status())) {
            boolean retired = tool.status() == ApprovalStatus.RETIRED.code();
            throw new ServiceException("工具「" + tool.name() + "」"
                    + (retired ? "已下架" : "未发布（当前：" + ApprovalStatus.labelOf(tool.status()) + "）")
                    + "，AI 生成链仅可调用已发布工具——请先在「工具管理」页完成发布，再到项目详情的「工具启用」页签重新启用",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    
    private ToolTestRunResult execute(Long operatorId, Long taskId, ToolInfo tool,
                                      String inputJson, String caller, String action) {
        if (tool.execKind() == null) {
            throw new ServiceException("文档类工具无执行配置，"
                    + (TRACE_CALLER_AI.equals(caller) ? "AI 生成链" : "试跑")
                    + "仅支持可执行工具——请在「工具管理」页选择可执行形态的工具后重试", BizErrorCode.VALIDATION_ERROR);
        }
        ToolExecKind kind = ToolExecKind.of(tool.execKind());

        String input = inputJson == null ? "" : inputJson;
        long startedAt = System.nanoTime();
        // Honest outbound desensitization facet, only meaningful for the
        // MCP_BRIDGE branch (the other shapes never hand a body to an
        // external MCP server, so their trace keeps null).
        Integer outboundMasked = null;
        ToolTestRunResult result = switch (kind) {
            case EXECUTOR -> executeExecutor(tool, input);
            case MCP_BRIDGE -> {
                McpBridgeCall bridge = executeMcpBridge(tool, input);
                outboundMasked = bridge.masked();
                yield bridge.result();
            }
            case GENKIT -> executeGenkit(tool, input);
            // The ANALYSIS shape has no url/script/server: falling through to
            // the HTTP branch would dial a target that does not exist and write
            // a trace row pretending an analysis ran, so it is refused by name
            // before any trace is recorded (same zero-trace guard posture as the
            // GENKIT argument guard).
            case ANALYSIS -> throw new ServiceException("工具「" + tool.name()
                    + "」是只读分析工具，仅在 AI 对话的分析通道内执行（由服务端按当前项目取数），"
                    + "不支持在工具试跑或通用调用面执行——请改从 AI 对话发起，或在「工具管理」页确认该工具形态", BizErrorCode.VALIDATION_ERROR);
            default -> executeHttp(tool, input, operatorId);
        };
        long durationMs = (System.nanoTime() - startedAt) / 1_000_000;

        Long traceServerId = kind == ToolExecKind.MCP_BRIDGE ? mcpServerIdOf(tool) : null;
        recordTraceAndAudit(operatorId, taskId, tool, input, result, durationMs,
                traceServerId, outboundMasked, caller, action);
        return new ToolTestRunResult(result.toolId(), result.toolName(), result.success(),
                result.output(), result.errorCategory(), result.errorCode(),
                result.errorMessage(), durationMs);
    }


    
    private ToolTestRunResult executeExecutor(ToolInfo tool, String input) {
        String script = jdbcTemplate.queryForObject(
                "SELECT COALESCE(body, '') FROM tp_tool WHERE id = ?", String.class, tool.id());
        if (script.isBlank()) {
            throw new ServiceException("EXECUTOR 工具脚本正文为空，请先在「工具管理」页「编辑工具」弹窗填写脚本正文后重新「发起试跑」",
                    BizErrorCode.VALIDATION_ERROR);
        }
        Map<String, Object> spec = parseSpec(tool);
        long budgetMs = Math.min(tool.timeoutMs(), EXECUTOR_SANDBOX_MAX_MS);

        String envelope = buildToolEnvelope(tool, script, input, budgetMs);
        long taskId = execTaskService.createToolTestRun(tool.projectId() == null ? 0L : tool.projectId(),
                envelope);

        if (!executorService.hasOnlineExecutor()) {
            return fail(tool, "executor", null,
                    "当前无可用执行器（未注册或全部离线），试跑任务已排队等待领取——执行器上线后重新试跑即可");
        }

        long deadline = System.currentTimeMillis() + budgetMs;
        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> snap = jdbcTemplate.queryForList(
                    "SELECT status, log, error_detail FROM tp_exec_task WHERE id = ?", taskId)
                    .stream().findFirst().orElse(null);
            if (snap != null) {
                int status = ((Number) snap.get("status")).intValue();
                if (status == 3) {
                    String out = (String) snap.get("log");
                    return ToolTestRunResult.ok(tool.id(), tool.name(),
                            out == null ? "" : out, 0L);
                }
                if (status == 4 || status == 5 || status == 6) {
                    String detail = (String) snap.get("error_detail");
                    return fail(tool, "executor", null,
                            "执行器沙箱运行失败：" + (detail == null || detail.isBlank()
                                    ? "脚本非零退出（详情见执行任务记录）" : detail));
                }
            }
            try {
                Thread.sleep(EXECUTOR_POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return fail(tool, "timeout", BizErrorCode.AI_TIMEOUT, "沙箱试跑被中断");
            }
        }
        execTaskService.timeoutToolRun(taskId);
        return fail(tool, "timeout", BizErrorCode.AI_TIMEOUT,
                "执行器沙箱在 " + budgetMs + " 毫秒内未回传结果（沙箱单次限时 "
                        + budgetMs / 1000 + " 秒，超时已终止），请精简脚本或调大该工具的执行超时后重试");
    }

    
    private String buildToolEnvelope(ToolInfo tool, String script, String input, long budgetMs) {
        Map<String, Object> spec = parseSpec(tool);
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("kind", "tool-test-run");
        envelope.put("toolId", tool.id());
        envelope.put("scriptEntry", stringOf(spec.get("scriptEntry")));
        envelope.put("script", script);
        envelope.put("input", input);
        envelope.put("timeoutMs", budgetMs);
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (Exception e) {
            throw new ServiceException("工具试跑任务信封构建失败：执行配置或入参无法序列化"
                    + "——请在「工具管理」页「编辑工具」弹窗修正脚本与入参 JSON 后重试", BizErrorCode.VALIDATION_ERROR);
        }
    }


    
    private ToolTestRunResult executeGenkit(ToolInfo tool, String input) {
        Map<String, Object> spec = parseSpec(tool);
        String function = stringOf(spec.get("function"));
        Map<String, Object> args = parseGenkitArgs(input);
        try {
            return ToolTestRunResult.ok(tool.id(), tool.name(), GenKit.invoke(function, args), 0L);
        } catch (ServiceException e) {
            throw new ServiceException(e.getMessage(), BizErrorCode.VALIDATION_ERROR);
        }
    }

    
    private Map<String, Object> parseGenkitArgs(String input) {
        if (input == null || input.isBlank()) {
            return Map.of();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> args = objectMapper.readValue(input, Map.class);
            return args;
        } catch (Exception e) {
            throw new ServiceException("GENKIT 造数工具入参必须是 JSON 对象——示例：{\"seed\":123} 或 "
                    + "{\"text\":\"原文\"}，请按正确格式修正入参后重试", BizErrorCode.VALIDATION_ERROR);
        }
    }


    /**
 * The returned seam also carries the honest outbound desensitization
 * facet ({@link McpBridgeCall}) consumed by the trace/audit single point.
 */
    private McpBridgeCall executeMcpBridge(ToolInfo tool, String input) {
        Map<String, Object> spec = parseSpec(tool);
        Long serverId = longOf(spec.get("serverId"));
        String remoteTool = stringOf(spec.get("toolName"));
        McpAssemblyServer server = serverId == null ? null
                : mcpConsumptionService.getAssemblyServers().stream()
                        .filter(s -> serverId.equals(s.serverId()))
                        .findFirst().orElse(null);
        if (server == null) {
            throw new ServiceException("MCP 服务 " + serverId + " 不存在或已停用"
                    + "——请打开「MCP 服务」页核对该服务的注册与启用状态", BizErrorCode.VALIDATION_ERROR);
        }
        if (remoteTool == null || !server.toolNames().contains(remoteTool)) {
            throw new ServiceException("远端工具「" + remoteTool + "」不在 MCP 服务「"
                    + server.name() + "」的白名单勾选集内（已拒绝）——请打开「MCP 服务」页，"
                    + "对该服务「连接测试」成功后在「工具勾选」中补勾该工具再重试", BizErrorCode.VALIDATION_ERROR);
        }
        String endpoint = validateBridgeEndpoint(server.endpoint());
        JsonNode arguments = parseArguments(input);
        // : the body of a tools/call that leaves the platform toward an
        // external MCP server always runs through the shared outbound gate
        // (single source, fail-closed — a masking failure refuses the call,
        // it never degrades to sending the raw body).
        McpOutboundDesensitizeGate outboundGate = McpOutboundDesensitizeGate.fromRawValue(
                outboundConfigReader.getString(McpOutboundDesensitizeGate.ENFORCE_KEY, null));
        try {
            arguments = outboundGate.prepareNode(arguments, objectMapper);
        } catch (RuntimeException e) {
            // Refusal path: the body never left, so the trace facet stays
            // null ("no outbound body") instead of pretending 1 or 0.
            return new McpBridgeCall(fail(tool, "protocol", null,
                    "出站内容脱敏失败，本次调用已拒绝"), null);
        }
        // The body handed to the MCP client is the prepared one either way;
        // the honest facet comes from the gate that prepared it (single
        // source — re-reading the config key here is banned).
        Integer outboundMasked = outboundGate.enforce() ? 1 : 0;

        McpToolCallOutcome outcome = mcpHandshakeClient.callTool(endpoint, server.auth(),
                remoteTool, arguments, Duration.ofMillis(tool.timeoutMs()));
        if (outcome.success()) {
            return new McpBridgeCall(
                    ToolTestRunResult.ok(tool.id(), tool.name(), outcome.text(), 0L),
                    outboundMasked);
        }
        return new McpBridgeCall(fail(tool, outcome.category(), null, outcome.message()),
                outboundMasked);
    }

    
    private Long mcpServerIdOf(ToolInfo tool) {
        try {
            return longOf(parseSpec(tool).get("serverId"));
        } catch (ServiceException e) {
            return null;
        }
    }

    
    private String validateBridgeEndpoint(String endpoint) {
        try {
            return endpointPolicy.validate(endpoint, "端点");
        } catch (ServiceException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage().replaceFirst("^MCP ", "");
            throw new ServiceException("MCP 桥接目标端点非法：" + msg
                    + "——请在「MCP 服务」页「编辑 MCP 服务」弹窗把端点改为可访问的 http/https 地址后重新保存", BizErrorCode.VALIDATION_ERROR);
        }
    }

    
    private JsonNode parseArguments(String input) {
        if (input == null || input.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode node = objectMapper.readTree(input);
            if (node.isObject()) {
                return node;
            }
        } catch (IOException e) {
        }
        throw new ServiceException("MCP_BRIDGE 试跑入参必须是 JSON 对象（远端工具 arguments）——"
                + "示例：{\"key\":\"value\"}，请按该格式重新填写入参后重试", BizErrorCode.VALIDATION_ERROR);
    }

    private static Long longOf(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }


    private ToolTestRunResult executeHttp(ToolInfo tool, String input, Long operatorId) {
        Map<String, Object> spec = parseSpec(tool);
        String url = doorAuthority.localize(validateTargetUrl(stringOf(spec.get("url"))));
        String authValue = resolveAuthRef(stringOf(spec.get("authRef")));

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(tool.timeoutMs()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(input, StandardCharsets.UTF_8));
        if (authValue != null) {
            builder.header("Authorization",
                    authValue.startsWith("Bearer ") ? authValue : "Bearer " + authValue);
        }
        // Identity travels on the trusted internal header, never inside the model-composed body:
        // a tool that reads this application's own doors has to be judged as the person who asked,
        // not as an unattributed system caller. Absent operator means no header is added, and the
        // door on the other end decides whether it can work without an identity.
        if (operatorId != null) {
            builder.header(OperatorContext.OPERATOR_ID_HEADER, String.valueOf(operatorId));
        }

        try {
            HttpResponse<InputStream> resp =
                    httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            CappedBody capped = readCapped(resp.body());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                return fail(tool, "http", null, "目标服务返回 HTTP " + resp.statusCode()
                        + "，响应：" + clipForError(capped.text(), 200));
            }
            if (capped.truncated()) {
                return ToolTestRunResult.ok(tool.id(), tool.name(), capped.text(), 0L);
            }
            try {
                objectMapper.readTree(capped.text());
            } catch (IOException e) {
                return fail(tool, "format", null, "目标响应不是 JSON，无法解析为工具出参。原文（截断）："
                        + clipForError(capped.text(), 300));
            }
            return ToolTestRunResult.ok(tool.id(), tool.name(), capped.text(), 0L);
        } catch (HttpTimeoutException e) {
            return fail(tool, "timeout", BizErrorCode.AI_TIMEOUT,
                    "目标服务在 " + tool.timeoutMs() + " 毫秒内未响应（试跑超时），"
                            + "请检查目标地址连通性或调大该工具的执行超时");
        } catch (UnknownHostException e) {
            return fail(tool, "network", null, "无法连接目标服务（域名解析失败：" + e.getMessage() + "）");
        } catch (ConnectException e) {
            return fail(tool, "network", null, "无法连接目标服务（连接被拒绝：" + e.getMessage()
                    + "），请检查地址与端口是否正确、服务是否已启动");
        } catch (IOException e) {
            return fail(tool, "network", null, "无法连接目标服务（" + e.getMessage() + "）");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fail(tool, "network", null, "试跑被中断");
        }
    }

    
    private CappedBody readCapped(InputStream in) throws IOException {
        try (InputStream stream = in) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while (buf.size() < MAX_OUTPUT_BYTES + 1 && (n = stream.read(chunk)) != -1) {
                int room = MAX_OUTPUT_BYTES + 1 - buf.size();
                buf.write(chunk, 0, Math.min(n, room));
            }
            if (buf.size() <= MAX_OUTPUT_BYTES) {
                return new CappedBody(buf.toString(StandardCharsets.UTF_8), false);
            }
            String cut = new String(buf.toByteArray(), 0, MAX_OUTPUT_BYTES, StandardCharsets.UTF_8);
            return new CappedBody(cut + TRUNCATED_SUFFIX, true);
        }
    }


    private void recordTraceAndAudit(Long operatorId, Long taskId, ToolInfo tool, String input,
                                     ToolTestRunResult result, long durationMs, Long serverId,
                                     Integer outboundMasked,
                                     String caller, String action) {
        mcpConsumptionService.recordInvocations(List.of(new McpInvocationInput(
                caller, operatorId, taskId, tool.id(), serverId, tool.name(),
                result.success() ? 1 : 0, durationMs, result.errorMessage(), input,
                // Honest outbound facet of the MCP_BRIDGE branch (1 scrubbed /
                // 0 explicit downgrade / null = no outbound body left: the
                // other execution shapes and every refusal path keep null).
                outboundMasked)));

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("id", tool.id());
        after.put("name", tool.name());
        after.put("success", result.success());
        after.put("durationMs", durationMs);
        if (taskId != null) {
            after.put("taskId", taskId);
        }
        if (result.errorCategory() != null) {
            after.put("errorCategory", result.errorCategory());
        }
        if (result.errorMessage() != null) {
            after.put("errorMessage", clipForError(result.errorMessage(), ERROR_MAX));
        }
        auditLogService.record(new AuditLogInput(operatorId, tool.projectId(), "tool",
                String.valueOf(tool.id()), action, null, after));
    }

    // ---- helpers ----

    private Map<String, Object> parseSpec(ToolInfo tool) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> spec = objectMapper.readValue(tool.execSpecJson(), Map.class);
            return spec;
        } catch (Exception e) {
            throw new ServiceException("工具执行配置不是合法 JSON 对象，请先在「工具管理」页「编辑工具」弹窗修正执行配置字段",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    
    private String validateTargetUrl(String raw) {
        try {
            return endpointPolicy.validate(raw, "url");
        } catch (ServiceException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage().replaceFirst("^MCP ", "");
            throw new ServiceException("工具执行目标地址非法：" + msg
                    + "——请在「工具管理」页「编辑工具」弹窗修正执行配置中的 url 后重试", BizErrorCode.VALIDATION_ERROR);
        }
    }

    
    private String resolveAuthRef(String authRef) {
        if (authRef == null || authRef.isBlank()) {
            return null;
        }
        return configCacheHolder.get(AUTH_REF_KEY_PREFIX + authRef.trim());
    }

    private ToolTestRunResult fail(ToolInfo tool, String category, Integer code, String message) {
        return ToolTestRunResult.fail(tool.id(), tool.name(), category, code, message, 0L);
    }

    private static String stringOf(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    
    private static String clipForError(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "…";
    }
}
