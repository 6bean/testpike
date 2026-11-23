package com.sixbean.testpike.aiservice.tool.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.QueryResultRemote;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.QueryTargetViewRemote;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatToolDef;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatToolFunction;
import com.sixbean.testpike.common.exception.ServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The built-in aggregate.query FC tool of the live dialog (AO batch P3
 * ; ruling "the model only composes intent — the server computes the
 * numbers"). Unlike tp_tool rows this one is synthetic and dialog-surface
 * only: the ReAct streaming loop appends its definition to every plan and
 * routes its calls straight here, never through the registered-tool index.
 * <p>Why not a seeded tp_tool row: the dial would need the project segment to
 * travel in the URL/query of an HTTP exec spec, i.e. the model would have to
 * supply projectId — exactly the identity-injection seam closed
 * (reserved keys in a model body are refused). The synthetic shape lets the
 * server hand its own trusted projectId to the core door instead.
 * <p>Execution is zero-local-query: the raw model arguments ride verbatim to
 * {@code POST /internal/query-spec/run} and every boundary (registry target,
 * DSL shape, column allow list, project scope, visibility re-check through
 * the same five-way-gate component as tool invoke) is enforced by the core
 * interpreter. Refusals come back as readable tool messages — never as
 * exceptions (fail-soft like ToolDispatcher; a hallucinated target is
 * the model's to correct against the vocabulary in the description).
 */
@Component
public class AggregateQueryTool {

    private static final Logger log = LoggerFactory.getLogger(AggregateQueryTool.class);

    /** FC tool name (same dotted built-in vocabulary as case.flow-guide). */
    public static final String NAME = "aggregate.query";

    /** DSL shape of the QuerySpec the model must produce (server re-reads it). */
    private static final String PARAMETERS_JSON = """
            {"type":"object","properties":{
              "target":{"type":"string","description":"登记表内只读目标键"},
              "filters":{"type":"array","items":{"type":"object",
                "properties":{"field":{"type":"string"},"value":{}}},
                "description":"等值过滤（AND），字段须属目标登记的维度列"},
              "groupBy":{"type":"array","items":{"type":"string"},
                "description":"分组维度列（登记的维度列）"},
              "aggregations":{"type":"array","items":{"type":"object",
                "properties":{"function":{"type":"string",
                  "description":"count|sum|avg|min|max"},
                  "column":{"type":"string","description":"度量列；count(*) 可省"}}},
                "description":"服务端实算的度量"},
              "orderBy":{"type":"array","items":{"type":"string"},
                "description":"\\"列[:asc|:desc]\\"，限结果列"},
              "limit":{"type":"integer","description":"行数上限（服务端按登记上限钳制）"}},
            "required":["target"]}""";

    /** Fallback description when the targets door is unreachable (fail-soft). */
    private static final String GENERIC_DESCRIPTION =
            "对平台数据做只读聚合统计（次数/求和/均值/最值，按维度分组）。"
                    + "参数=受控查询规格 JSON，只能引用登记目标与其允许列，"
                    + "非法目标或列会被拒绝并说明原因；结果由服务端计算，用于生成数据卡片。";

    private final CoreGateway coreGateway;
    private final ObjectMapper objectMapper;

    public AggregateQueryTool(CoreGateway coreGateway, ObjectMapper objectMapper) {
        this.coreGateway = coreGateway;
        this.objectMapper = objectMapper;
    }

    /**
     * FC tool definition, with the callable-target vocabulary embedded in the
     * description so the model composes against registry keys it can actually
     * hit (accuracy without a second guess loop). The vocabulary door carries
     * the same operator identity as the run door (core refuses anonymous);
     * without an actor, or when the door is unreachable, it degrades to the
     * generic description — the dialog keeps the tool rather than silently
     * losing the capability (fail-soft, no wasted round trip).
     */
    public ChatToolDef toolDef(Long actorId) {
        return new ChatToolDef(new ChatToolFunction(NAME, description(actorId),
                parseParameters()));
    }

    /**
 * Run one model-composed spec against the core interpreter door.
 *
 * @param projectId server-side segment of the running conversation —
 * never model-supplied (identity seam, same rule)
 * @param actorId the dialog user (X-Operator-Id; the door fails
 * closed without it, refused here one hop earlier —
 * same identity seam as )
 * @param argumentsJson model tool_call arguments, verbatim to the door
 */
    public ToolCallOutcome execute(long projectId, Long actorId, String argumentsJson) {
        if (actorId == null || actorId <= 0) {
            return ToolCallOutcome.fail(NAME,
                    "聚合查询缺少操作人身份（仅登录用户的对话可发起统计），已拒绝执行");
        }
        if (!isValidJsonObject(argumentsJson)) {
            return ToolCallOutcome.fail(NAME,
                    "聚合查询参数不是合法 JSON 对象（已拒绝执行）");
        }
        try {
            QueryResultRemote result = coreGateway.runQuerySpec(actorId, projectId, argumentsJson);
            return ToolCallOutcome.ok(NAME, objectMapper.writeValueAsString(result));
        } catch (ServiceException e) {
            // Interpreter refusals name the offending target/column — the
            // model reads them and self-corrects against the vocabulary.
            log.info("聚合查询被拒 projectId={} actorId={}: {}", projectId, actorId, e.getMessage());
            return ToolCallOutcome.fail(NAME, e.getMessage());
        } catch (Exception e) {
            log.warn("聚合查询执行失败 projectId={} actorId={}: {}", projectId, actorId,
                    e.getMessage());
            return ToolCallOutcome.fail(NAME, "聚合查询执行失败：" + e.getMessage());
        }
    }

    /** Whether a tool call name is this built-in (ReAct routing single test). */
    public static boolean isAggregateCall(String toolName) {
        return NAME.equals(toolName);
    }

    private String description(Long actorId) {
        if (actorId == null || actorId <= 0) {
            // No identity to ask the vocabulary door with — generic shape only.
            return GENERIC_DESCRIPTION;
        }
        List<QueryTargetViewRemote> targets;
        try {
            targets = coreGateway.getQueryTargets(actorId);
        } catch (Exception e) {
            // The vocabulary door is a nice-to-have on the request side; the
            // run door never depends on it (fail-soft, refusal at run keeps
            // naming the real boundary).
            log.warn("聚合查询目标清单不可达，降级为通用描述: {}", e.getMessage());
            return GENERIC_DESCRIPTION;
        }
        if (targets.isEmpty()) {
            return GENERIC_DESCRIPTION;
        }
        StringBuilder sb = new StringBuilder(GENERIC_DESCRIPTION)
                .append("可用目标：");
        for (QueryTargetViewRemote t : targets) {
            sb.append('【').append(t.targetKey())
                    .append(" 维度=").append(nullToEmpty(t.dimensionColumns()))
                    .append(" 度量=").append(nullToEmpty(t.measureColumns()))
                    .append(" 上限=").append(t.maxRows()).append('】');
        }
        return sb.toString();
    }

    private static String nullToEmpty(String raw) {
        return raw == null ? "" : raw;
    }

    private boolean isValidJsonObject(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return false;
        }
        try {
            return objectMapper.readTree(argumentsJson).isObject();
        } catch (Exception e) {
            return false;
        }
    }

    private com.fasterxml.jackson.databind.JsonNode parseParameters() {
        try {
            return objectMapper.readTree(PARAMETERS_JSON);
        } catch (Exception e) {
            // A broken schema here is a development bug, not runtime state —
            // same posture as ExploreBuiltinToolCatalog.parse.
            throw new IllegalStateException("内置聚合查询工具 schema 非法（开发期缺陷）", e);
        }
    }
}
