package com.sixbean.testpike.aiservice.gen.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.doc.domain.component.ContextBudgetPlanner;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway.ApiEndpointBrief;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway.ApiParamBrief;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway.ApiSpecBrief;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway.MockServingBrief;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway.PageRows;
import com.sixbean.testpike.aiservice.llm.domain.component.BudgetedPromptSegment;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmEndpointConfig;
import com.sixbean.testpike.aiservice.llm.domain.component.SysConfigBooleans;
import com.sixbean.testpike.aiservice.llm.domain.component.TokenEstimator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * assertion truth-source bridge, level 1 (contract schema).
 * <p>What it injects: for interface-type case generation the prompt gains the
 * project's latest contract rows (method + path + parameter names + response
 * field names from tp_api_spec) joined with measured mock samples (response
 * payload snapshots of the same method+path, tp_mock_hit-log plane through the
 * serving inventory read), so assertions cite real contract keys instead of
 * invented ones (V428 head-note).
 * <p>Gate {@value #KEY_ENABLED}: read through {@link SysConfigBooleans#parse}
 * with a factory default of ON; an absent or dirty row keeps it on, an
 * explicit 0/false turns the whole read path off.
 * <p>fail-soft contract (DdlDictBridge/McpKnowledgeBridge same baseline): both
 * reads ride one try — any exception yields an empty segment and the
 * generation chain is never interrupted. An empty contract store short-circuits
 * before the measured read, so projects without contracts keep the legacy
 * prompt bytes untouched.
 * <p>Budget single rule (one deduction only, same as the A4 few-shot path):
 * min(ai.doc.token-budget, endpoint prompt_limit_tokens) with whole-block
 * tail dropping, composed through the shared {@link BudgetedPromptSegment}
 * (R40 single source — this bridge is its third consumer, the extract that
 * retired the third copy of the loop).
 */
@Component
public class ContractTruthSourceBridge {

    private static final Logger log = LoggerFactory.getLogger(ContractTruthSourceBridge.class);

    /** Truth-source gate K8 key (V428 seed 'true', hot-reloaded per read). */
    public static final String KEY_ENABLED = "ai.gen.truth-source.api-spec-schema";

    /** Page window per read (management-plane display-scale contract; core caps size at 200). */
    static final int PAGE_SIZE = 100;

    /** Measured sample payload cap per row (chars) — a sample, not a schema dump. */
    static final int SAMPLE_MAX_CHARS = 240;

    /** Section header (anti-hallucination semantics: real keys are the evidence, invention is not). */
    static final String SECTION_HEADER = "接口契约与实测样例参考（读取自本项目最新接口契约与最近的实测响应样例；"
            + "断言字段必须以下列真实字段名为据、禁凭记忆自造字段名）：\n";

    private final CoreGateway coreGateway;
    private final TokenEstimator estimator;
    private final ObjectMapper schemaReader = new ObjectMapper();

    public ContractTruthSourceBridge(CoreGateway coreGateway, TokenEstimator estimator) {
        this.coreGateway = coreGateway;
        this.estimator = estimator;
    }

    /**
 * Build the contract truth-source segment for one generation task.
 *
 * @param projectId project segment key of the H1 isolation
 * @param endpoint routed endpoint (promptLimitTokens joins the budget min;
 * null = no model-side cap)
 * @return injectable segment, or "" when the gate is off / the project has
 * no contracts / any read failed
 */
    public String contractSegment(long projectId, LlmEndpointConfig endpoint) {
        if (!SysConfigBooleans.parse(coreGateway.getSysConfig(KEY_ENABLED), true)) {
            return "";
        }
        List<ApiSpecBrief> specs = new ArrayList<>();
        Map<String, MockServingBrief> measured = new LinkedHashMap<>();
        if (!readTruth(projectId, specs, measured)) {
            return "";
        }
        if (specs.isEmpty()) {
            // No contract store in this project: zero injection and the
            // measured read is never issued (legacy prompt bytes).
            return "";
        }
        List<String> blocks = new ArrayList<>();
        for (ApiSpecBrief spec : specs) {
            for (ApiEndpointBrief endpointRow : endpointsOf(spec)) {
                blocks.add(endpointBlock(spec, endpointRow,
                        measured.get(joinKey(endpointRow.method(), endpointRow.path()))));
            }
        }
        return BudgetedPromptSegment.compose(
                "contract brief projectId=" + projectId, estimator,
                ContextBudgetPlanner.tokenBudgetOf(
                        coreGateway.getSysConfig(ContextBudgetPlanner.BUDGET_CONFIG_KEY)),
                endpoint == null ? null : endpoint.promptLimitTokens(),
                SECTION_HEADER, blocks);
    }

    /**
     * Both truth reads ride one try: any failure returns false and the caller
     * injects nothing (V428 head-note honesty). The measured read is skipped
     * when the project carries no contracts, so a contract-less project never
     * issues the second call.
     */
    private boolean readTruth(long projectId, List<ApiSpecBrief> specsOut,
                              Map<String, MockServingBrief> measuredOut) {
        try {
            PageRows<ApiSpecBrief> window = coreGateway.listApiSpecs(projectId, 1, PAGE_SIZE);
            specsOut.addAll(window == null || window.list() == null
                    ? List.of() : window.list());
            if (!specsOut.isEmpty()) {
                measuredOut.putAll(indexMeasured(
                        coreGateway.listMockServingInventory(projectId, 1, PAGE_SIZE)));
            }
            return true;
        } catch (Exception e) {
            log.warn("contract truth-source read failed (zero injection this run, "
                    + "generation unaffected) projectId={}: {}", projectId, e.getMessage());
            return false;
        }
    }

    private static List<ApiEndpointBrief> endpointsOf(ApiSpecBrief spec) {
        return spec == null || spec.endpoints() == null ? List.of() : spec.endpoints();
    }

    /** Join key of contract endpoint ↔ measured mock row: verb (case-insensitive) + exact path. */
    private static String joinKey(String method, String path) {
        return (method == null ? "" : method.trim().toUpperCase()) + "|" + path;
    }

    private static Map<String, MockServingBrief> indexMeasured(PageRows<MockServingBrief> rows) {
        Map<String, MockServingBrief> index = new LinkedHashMap<>();
        for (MockServingBrief row : rows == null || rows.list() == null
                ? List.<MockServingBrief>of() : rows.list()) {
            if (row == null) {
                continue;
            }
            index.putIfAbsent(joinKey(row.method(), row.pathPattern()), row);
        }
        return index;
    }

    private String endpointBlock(ApiSpecBrief spec, ApiEndpointBrief row, MockServingBrief hit) {
        StringBuilder block = new StringBuilder();
        block.append(row.method() == null ? "?" : row.method().trim().toUpperCase())
                .append(' ').append(row.path())
                .append("（契约 ").append(spec.name() == null ? "?" : spec.name());
        if (spec.specVersion() != null) {
            block.append(" v").append(spec.specVersion());
        }
        block.append("）");
        String params = parameterNames(row);
        if (!params.isEmpty()) {
            block.append(" 参数: ").append(params);
        }
        String fields = responseFieldNames(row);
        if (!fields.isEmpty()) {
            block.append(" 响应字段: ").append(fields);
        }
        if (hit != null) {
            block.append(" 实测样例: ").append(sampleOf(hit));
            if (hit.hits7d() != null) {
                block.append("（近7日命中 ").append(hit.hits7d()).append(" 次）");
            }
        }
        return block.append('\n').toString();
    }

    private static String parameterNames(ApiEndpointBrief row) {
        List<String> names = new ArrayList<>();
        for (ApiParamBrief param : row.parameters() == null
                ? List.<ApiParamBrief>of() : row.parameters()) {
            if (param != null && param.name() != null && !param.name().isBlank()) {
                names.add(param.name());
            }
        }
        return String.join(", ", names);
    }

    /**
     * Response field names per status: the schema text already has its $ref
     * resolved by the core parser (OpenApiSpecParser), so the property keys are
     * the real wire field names. A schema that will not parse contributes no
     * field line for that endpoint — never a thrown exception (fail-soft).
     */
    private String responseFieldNames(ApiEndpointBrief row) {
        Map<String, String> schemas = row.responseSchemas() == null
                ? Map.of() : row.responseSchemas();
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, String> entry : schemas.entrySet()) {
            List<String> fields = propertiesOf(entry.getValue());
            if (!fields.isEmpty()) {
                parts.add(entry.getKey() + ": " + String.join(", ", fields));
            }
        }
        return String.join(" | ", parts);
    }

    private List<String> propertiesOf(String schemaJson) {
        if (schemaJson == null || schemaJson.isBlank()) {
            return List.of();
        }
        try {
            JsonNode properties = schemaReader.readTree(schemaJson).path("properties");
            if (!properties.isObject()) {
                return List.of();
            }
            List<String> names = new ArrayList<>();
            properties.fieldNames().forEachRemaining(names::add);
            return names;
        } catch (Exception unparsable) {
            return List.of();
        }
    }

    private static String sampleOf(MockServingBrief hit) {
        String payload = hit.responseJson() == null ? "" : hit.responseJson().strip();
        if (payload.length() > SAMPLE_MAX_CHARS) {
            payload = payload.substring(0, SAMPLE_MAX_CHARS) + "…";
        }
        return payload.isEmpty() ? "（样例正文缺）" : payload;
    }
}
