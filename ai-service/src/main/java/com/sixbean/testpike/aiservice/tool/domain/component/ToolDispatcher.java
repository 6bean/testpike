package com.sixbean.testpike.aiservice.tool.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.ToolFcAssemblyRemoteItem;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.ToolInvokeResultRemote;
import com.sixbean.testpike.aiservice.llm.domain.LlmToolCall;
import com.sixbean.testpike.aiservice.mcp.domain.component.McpTaskToolBag;
import com.sixbean.testpike.aiservice.mcp.domain.component.McpToolCallResult;
import com.sixbean.testpike.aiservice.tool.domain.FcPlan;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * <ul>
 * </ul>
 *
 */
@Component
public class ToolDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ToolDispatcher.class);

    
    static final int KIND_HTTP = 1;
    static final int KIND_EXECUTOR = 2;
    static final int KIND_MCP_BRIDGE = 3;
    static final int KIND_GENKIT = 4;
    /**
 * exec_kind=5: read-only analysis rows. They never run through the generic
 * tool-invoke link (core refuses them there, fail-closed); the dialog routes
 * them to the project-sliced analysis door with the server-carried segment.
 */
    static final int KIND_ANALYSIS = 5;

    private final CoreGateway coreGateway;
    private final ObjectMapper objectMapper;
    /** AP-1(1) budget folder (nullable = legacy no-fold wiring, zero drift). */
    private final ToolResultBudgetFolder folder;

    public ToolDispatcher(CoreGateway coreGateway, ObjectMapper objectMapper) {
        this(coreGateway, objectMapper, null);
    }

    @Autowired
    public ToolDispatcher(CoreGateway coreGateway, ObjectMapper objectMapper,
                          ToolResultBudgetFolder folder) {
        this.coreGateway = coreGateway;
        this.objectMapper = objectMapper;
        this.folder = folder;
    }

    
    public ToolCallOutcome dispatch(FcPlan plan, LlmToolCall call,
                                    Long actorId, Long taskId, McpTaskToolBag mcpBag) {
        return dispatch(plan, call, actorId, taskId, mcpBag, null);
    }

    
    public ToolCallOutcome dispatch(FcPlan plan, LlmToolCall call,
                                    Long actorId, Long taskId, McpTaskToolBag mcpBag,
                                    String confirmToken) {
        return dispatch(plan, call, actorId, taskId, mcpBag, confirmToken, null);
    }

    /**
 * Slice-carrying dispatch (analysis channel + API pass-through read):
 * {@code projectId} is the server-side conversation segment — the analysis
 * door reads one project's data, and the pass-through read door stamps its
 * project anchor from the same segment (see {@link PassThroughReadAnchor});
 * in both cases the model must never be able to name a project (identity
 * seam, same rule as the built-in aggregate.query tool). Callers without a
 * segment (async generation link) keep the old overload and thus get an
 * honest refusal on analysis rows, never a silent half-run.
 */
    public ToolCallOutcome dispatch(FcPlan plan, LlmToolCall call,
                                    Long actorId, Long taskId, McpTaskToolBag mcpBag,
                                    String confirmToken, Long projectId) {
        return dispatch(plan, call, actorId, taskId, mcpBag, confirmToken, projectId, null);
    }

    /**
 * Session-carrying dispatch : {@code chatSessionId}
 * joins the server-side trio stamped onto the dialog OCR read rows
 * ({@link DialogOcrReadAnchor}). Only the conversation orchestrator calls
 * this overload; the async generation link keeps the 7-arg form (session
 * = null), where the stamp STRIPS model-claimed anchors instead of
 * stamping — the doors then refuse fail-closed, never a forged session.
 */
    public ToolCallOutcome dispatch(FcPlan plan, LlmToolCall call,
                                    Long actorId, Long taskId, McpTaskToolBag mcpBag,
                                    String confirmToken, Long projectId, Long chatSessionId) {
        ToolFcAssemblyRemoteItem item = plan.index().get(call.name());
        if (item == null) {
            return ToolCallOutcome.fail(call.name(),
                    "工具「" + call.name() + "」不在项目启用清单（已拒绝执行）");
        }
        if (!isValidJsonArguments(call.argumentsJson())) {
            return ToolCallOutcome.fail(item.name(),
                    "工具「" + item.name() + "」参数体不是合法 JSON（已拒绝执行）");
        }
        boolean tokenPresent = confirmToken != null && !confirmToken.isBlank();
        if (item.write() && (!tokenPresent || item.execKind() != null
                && item.execKind() == KIND_MCP_BRIDGE)) {
            return ToolCallOutcome.fail(item.name(), tokenPresent
                    ? "工具「" + item.name() + "」具有写语义（x-write），MCP 桥接执行面无确认票校验件（已拒绝执行）"
                    : "工具「" + item.name() + "」具有写语义（x-write），生成链无人确证默认拒绝执行");
        }
        Integer kind = item.execKind();
        if (kind == null) {
            return ToolCallOutcome.fail(item.name(),
                    "工具「" + item.name() + "」执行形态缺失，无法路由（已拒绝执行）");
        }
        ToolCallOutcome routed = switch (kind) {
            case KIND_HTTP, KIND_EXECUTOR, KIND_GENKIT ->
                    invokeViaCore(item, call, actorId, taskId, confirmToken, projectId, chatSessionId);
            case KIND_MCP_BRIDGE -> invokeViaMcpBridge(item, call, taskId, mcpBag);
            case KIND_ANALYSIS -> invokeViaAnalysis(item, call, actorId, projectId);
            default -> ToolCallOutcome.fail(item.name(),
                    "工具「" + item.name() + "」执行形态未知（kind=" + kind + "，已拒绝执行）");
        };
        // AP-1(1): single backfill choke point — oversized results degrade to
        // a head/tail window + retrieval anchor before entering the dialog packet.
        return folder == null ? routed : folder.bound(routed, taskId, projectId);
    }

    /**
 * HTTP/EXECUTOR link: the core invoke endpoint (the execution sandbox lives on the
 * core/executor side; this class carries zero execution logic).
 * The only execution-logic exception is the pass-through read anchor: the
 * session segment is stamped into the nested arguments before the payload
 * leaves this service ({@link PassThroughReadAnchor}), so a conversation can
 * read its own project's cataloged endpoints without the model ever naming
 * — or forging — a project id. Every other tool's bag rides on byte-identical.
 */
    private ToolCallOutcome invokeViaCore(ToolFcAssemblyRemoteItem item, LlmToolCall call,
                                          Long actorId, Long taskId, String confirmToken,
                                          Long projectId, Long chatSessionId) {
        ToolInvokeResultRemote result;
        try {
            String input = DialogOcrReadAnchor.stamp(item.name(),
                    PassThroughReadAnchor.stamp(
                            item.name(), call.argumentsJson(), projectId, objectMapper),
                    chatSessionId, projectId, actorId, objectMapper);
            // : the same server-side segment that the anchors stamp into the
            // payload also rides on the envelope, so the core identity guard trusts
            // it for system-level rows (project_id NULL) instead of reading the
            // stamped value as a forgery. The model composes only `input` and can
            // never write this field.
            result = coreGateway.invokeTool(actorId, taskId, item.toolId(),
                    input, confirmToken, projectId);
        } catch (Exception e) {
            log.warn("工具 invoke 失败 tool={} taskId={}: {}", item.name(), taskId, e.getMessage());
            return ToolCallOutcome.fail(item.name(),
                    "工具「" + item.name() + "」执行失败：" + e.getMessage());
        }
        if (result.success()) {
            return ToolCallOutcome.ok(item.name(), result.output());
        }
        String category = result.errorCategory() == null ? "error" : result.errorCategory();
        String reason = result.errorMessage() == null ? "无错误详情" : result.errorMessage();
        return ToolCallOutcome.fail(item.name(),
                "工具「" + item.name() + "」执行失败[" + category + "]：" + reason);
    }

    
    private ToolCallOutcome invokeViaMcpBridge(ToolFcAssemblyRemoteItem item, LlmToolCall call,
                                               Long taskId, McpTaskToolBag mcpBag) {
        if (mcpBag == null) {
            return ToolCallOutcome.fail(item.name(),
                    "MCP 桥接工具「" + item.name() + "」缺少任务工具袋（桥接链未初始化，已拒绝执行）");
        }
        McpToolCallResult result = mcpBag.callBridgeTool(item.toolId(), call.name(),
                call.argumentsJson());
        if (result.success()) {
            return ToolCallOutcome.ok(item.name(), result.text());
        }
        return ToolCallOutcome.fail(item.name(),
                "工具「" + item.name() + "」执行失败：" + result.error());
    }

    /**
     * ANALYSIS link: verbatim filters to the project-sliced core door. The
     * function name comes from the assembly row (registry literal), never from
     * the model; refusals (visibility, unknown function, bad filter shape) ride
     * back as readable tool messages so the ReAct loop self-corrects — no
     * exception escapes into the generation chain (fail-soft baseline).
     */
    private ToolCallOutcome invokeViaAnalysis(ToolFcAssemblyRemoteItem item, LlmToolCall call,
                                              Long actorId, Long projectId) {
        if (projectId == null || projectId <= 0) {
            return ToolCallOutcome.fail(item.name(),
                    "工具「" + item.name() + "」是对话只读分析函数，本次调用没有携带对话所属项目切片（已拒绝执行）");
        }
        String function = item.functionName();
        if (function == null || function.isBlank()) {
            return ToolCallOutcome.fail(item.name(),
                    "分析工具「" + item.name() + "」的注册行缺少函数名（服务端注册不完整，已拒绝执行）");
        }
        try {
            Map<String, Object> result = coreGateway.runAnalysis(actorId, projectId,
                    function, call.argumentsJson());
            if (result == null) {
                return ToolCallOutcome.fail(item.name(),
                        "分析工具「" + item.name() + "」未返回结果（服务端结果为空，已拒绝采信）");
            }
            return ToolCallOutcome.ok(item.name(), objectMapper.writeValueAsString(result));
        } catch (Exception e) {
            log.warn("分析工具执行失败 tool={} projectId={}: {}", item.name(), projectId, e.getMessage());
            return ToolCallOutcome.fail(item.name(),
                    "工具「" + item.name() + "」执行失败：" + e.getMessage());
        }
    }

    
    private boolean isValidJsonArguments(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return true;
        }
        try {
            objectMapper.readTree(argumentsJson);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
