package com.sixbean.testpike.aiservice.tool.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatToolDef;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatToolFunction;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.util.DesensitizeUtils;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * AP-1(1) retrieval built-in meta-tool : when
 * {@link ToolResultBudgetFolder} folds an oversized tool result into a
 * head/tail window, the pointer names an anchor; the model calls this tool
 * with that anchor to re-fetch the verbatim full text inside its owning scope.
 * <p>Ownership is resolved from the anchor itself — the canonical key's file
 * segment embeds the owner written at upload time ({@code task{id}-…} for a
 * generation task, {@code dialog-…} for the dialog surface). A cross-task
 * read is refused BEFORE any storage hop and journaled with the mismatched
 * ids (trace lives here, never in the model-visible text). Dialog-scope
 * anchors are readable only in the dialog context (taskId null): per-session
 * isolation is a documented gap until a dialog id reaches the ReAct loop, so
 * the enforced granularity is task + context-type, as adjudicated.
 * <p>Read path: core's presigned view grant (same ArtifactStorage route the
 * upload used — one storage door, no second port). Fetched text is emitted
 * through the existing {@link DesensitizeUtils} single source, exactly like
 * the knowledge-ingest face, so retrieval can never leak a raw phone/email
 * back into the dialog. Any incident (bad shape, refused grant, missing
 * object) degrades to a readable refusal outcome — never a throw: the dialog
 * chain must survive a mis-anchored call (fail-soft posture shared with the
 * dispatcher).
 */
@Component
public class ToolResultRetrievalTool {

    /** FC tool name (the folded pointer refers to this name verbatim). */
    public static final String NAME = "retrieval";

    private static final Logger log = LoggerFactory.getLogger(ToolResultRetrievalTool.class);

    /** Canonical scene segment; anything else is not a tool-result dump. */
    private static final String SCENE_SEGMENT = "/tool-result/";

    /** StorageKeys uid prefix length (8 hex + '-'), stripped before the owner. */
    private static final int UID_SEGMENT_LENGTH = 9;

    private static final String PARAMETERS_JSON = """
            {"type":"object","properties":{
              "anchor":{"type":"string",
                "description":"逐字复制工具结果指针中的 anchor= 值"}}},
            "required":["anchor"]}""";

    private static final String DESCRIPTION =
            "回取此前被压缩为头尾窗口的超长工具结果全文。仅当某条工具结果带有"
                    + " anchor=… 指针且确实需要中间内容时调用；anchor 必须逐字取自该指针，"
                    + "禁止编造。只能回取当前任务/对话自己落盘的全文，读取其他任务的锚点会被拒绝。";

    private final CoreGateway coreGateway;
    private final ObjectMapper mapper;
    private final RestClient rest;

    public ToolResultRetrievalTool(CoreGateway coreGateway, ObjectMapper mapper,
                                   RestClient.Builder restClientBuilder) {
        this.coreGateway = coreGateway;
        this.mapper = mapper;
        this.rest = restClientBuilder.build();
    }

    /** Routing predicate for the ReAct loop (built-in, never dispatcher-routed). */
    public static boolean isRetrievalCall(String toolName) {
        return NAME.equals(toolName);
    }

    /** Definition mounted on every running FC plan (schema is static). */
    public ChatToolDef toolDef() {
        JsonNode parameters;
        try {
            parameters = mapper.readTree(PARAMETERS_JSON);
        } catch (com.fasterxml.jackson.core.JsonProcessingException brokenSchema) {
            throw new IllegalStateException("retrieval parameter schema must be valid JSON",
                    brokenSchema);
        }
        return new ChatToolDef(new ChatToolFunction(NAME, DESCRIPTION, parameters));
    }

    /**
 * Resolve one anchor against the running scope and fetch its full text.
 *
 * @param argumentsJson model tool_call arguments ({@code {"anchor":…}})
 * @param taskId owning generation task (null = dialog context)
 * @param projectId server-carried segment (trace context only — the
 * scene is system level, project never re-routes it)
 */
    public ToolCallOutcome execute(String argumentsJson, Long taskId, Long projectId) {
        String anchor = readAnchor(argumentsJson);
        if (anchor == null) {
            return ToolCallOutcome.fail(NAME,
                    "回取请求缺少 anchor 参数（须逐字使用工具结果指针中的 anchor 值）");
        }
        String owner = ownerSegment(anchor);
        if (owner == null) {
            return ToolCallOutcome.fail(NAME,
                    "anchor 不是有效的工具结果全文标识（须逐字复制指针中的 anchor= 值），已拒绝");
        }
        String refusal = checkOwnership(owner, anchor, taskId, projectId);
        if (refusal != null) {
            return ToolCallOutcome.fail(NAME, refusal);
        }
        return fetch(anchor);
    }

    /** Anchor string from the model body, or null for any unusable shape. */
    private String readAnchor(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return null;
        }
        try {
            JsonNode anchor = mapper.readTree(argumentsJson).path("anchor");
            return anchor.isTextual() && !anchor.asText().isBlank() ? anchor.asText() : null;
        } catch (Exception garbageBody) {
            return null;
        }
    }

    /**
     * Owner segment of a canonical tool-result key: last path segment minus
     * the storage uid prefix ({@code task42-….txt} / {@code dialog-….txt}).
     * Null for foreign scenes, short segments or unknown owner prefixes.
     */
    private static String ownerSegment(String anchor) {
        if (!anchor.contains(SCENE_SEGMENT)) {
            return null;
        }
        int slash = anchor.lastIndexOf('/');
        String last = anchor.substring(slash + 1);
        if (last.length() <= UID_SEGMENT_LENGTH || last.charAt(UID_SEGMENT_LENGTH - 1) != '-') {
            return null;
        }
        String owner = last.substring(UID_SEGMENT_LENGTH);
        return owner.startsWith("task") || owner.startsWith("dialog-") ? owner : null;
    }

    /** Refusal text when the anchor owner does not match the running scope. */
    private String checkOwnership(String owner, String anchor, Long taskId, Long projectId) {
        if (owner.startsWith("dialog-")) {
            if (taskId == null) {
                return null;
            }
            log.info("对话级全文锚被任务语境回取拒绝 taskId={} projectId={} anchor={}",
                    taskId, projectId, anchor);
            return "拒绝回取：该全文是对话落盘产物，生成任务只能回取本任务自己的工具结果";
        }
        long ownerId = Long.parseLong(owner.substring("task".length(),
                owner.indexOf('-', "task".length())));
        if (taskId == null) {
            return "拒绝回取：当前对话语境没有任务身份，无法回取任务 " + ownerId + " 的工具结果全文";
        }
        if (taskId != ownerId) {
            log.warn("跨任务回取被拒（越权留痕） currentTaskId={} ownerTaskId={} projectId={}"
                    + " anchor={}", taskId, ownerId, projectId, anchor);
            return "拒绝回取：该工具结果全文属于任务 " + ownerId + "，与当前任务 " + taskId
                    + " 不一致，只能回取本任务自己的全文";
        }
        return null;
    }

    /** Presigned view grant via the core door, then one raw GET for the bytes. */
    private ToolCallOutcome fetch(String anchor) {
        String url;
        try {
            url = coreGateway.grantToolArtifactView(anchor);
        } catch (ServiceException refused) {
            return ToolCallOutcome.fail(NAME, "全文回取被拒绝：" + refused.getMessage());
        } catch (Exception unreachable) {
            log.warn("全文回取授权失败 anchor={}: {}", anchor, unreachable.getMessage());
            return ToolCallOutcome.fail(NAME, "全文回取暂不可用（存储通道未响应），可稍后重试");
        }
        String text;
        try {
            // Bytes then explicit UTF-8 — the upload PUT wrote raw UTF-8 with a
            // bare text/plain content type, so the download side must not let
            // the converter's missing-charset fallback mangle CJK payloads.
            byte[] raw = rest.get().uri(URI.create(url)).retrieve().body(byte[].class);
            text = raw == null ? null : new String(raw, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception gone) {
            log.info("全文对象读取失败 anchor={}: {}", anchor, gone.getMessage());
            return ToolCallOutcome.fail(NAME,
                    "全文对象不存在或已被保留策略清理（anchor=" + anchor + "）");
        }
        if (text == null) {
            return ToolCallOutcome.fail(NAME, "全文对象读取为空（anchor=" + anchor + "）");
        }
        return ToolCallOutcome.ok(NAME, "【已携锚回取全文，按全局脱敏规则处理】\n"
                + DesensitizeUtils.maskText(text));
    }
}
