package com.sixbean.testpike.core.analysis.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.genkit.GenFunction;
import com.sixbean.testpike.core.analysis.domain.service.SufficiencyAnalysisService;
import com.sixbean.testpike.core.plans.domain.component.RehearsalDraftComposer;
import com.sixbean.testpike.core.plans.domain.component.RehearsalRiskReportComposer;
import com.sixbean.testpike.core.tool.domain.component.ToolAccessGate;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Single dispatch point of the read-only analysis channel :
 * a function name plus optional filters in, one computed view out.
 * <p>The callable set is derived from the function registry itself — only the
 * entries the registry marks as read-only analysis can run here, so the door
 * vocabulary and the registry can never drift apart and a data-fabrication
 * function can never be dialed through the analysis channel.
 * <p>Order of judgement: project visibility of the segment first (a caller who
 * cannot see the project never learns anything from a refusal message), then
 * the function name, then the filter shape. Each refusal names what it refused
 * and what is accepted, so the caller can self-correct in one step.
 * <p>Pure reads: no trace, no audit, no state machine — the numbers come from
 * {@link SufficiencyAnalysisService} and its existing sources.
 */
@Component
public class AnalysisFunctionDispatcher {

    /** Function name -> filter keys that function actually honours. */
    private static final Map<String, Set<String>> DECLARED_ARGS = declaredArgs();

    private final SufficiencyAnalysisService readService;
    private final RehearsalDraftComposer rehearsalComposer;
    private final RehearsalRiskReportComposer riskReportComposer;
    private final ToolAccessGate accessGate;

    public AnalysisFunctionDispatcher(SufficiencyAnalysisService readService,
                                      RehearsalDraftComposer rehearsalComposer,
                                      RehearsalRiskReportComposer riskReportComposer,
                                      ToolAccessGate accessGate) {
        this.readService = readService;
        this.rehearsalComposer = rehearsalComposer;
        this.riskReportComposer = riskReportComposer;
        this.accessGate = accessGate;
    }

    /** Callable vocabulary (door description for the dialog tool assembly). */
    public Set<String> callableFunctions() {
        return new TreeSet<>(DECLARED_ARGS.keySet());
    }

    /**
 * Run one analysis function over one project segment as one operator.
 *
 * @param projectId the segment the server carries for this conversation turn
 * @param operatorId authenticated operator; visibility is re-checked here
 * @param function registry name of a read-only analysis function
 * @param args filter object, nullable = whole project
 * @return the computed view (a {@code CoverageSummaryView} or an
 * {@code AssertionDensityView}), serialized as-is to the caller
 */
    public Object run(long projectId, Long operatorId, String function, JsonNode args) {
        accessGate.requireProjectVisible(projectId, operatorId);
        String name = function == null ? "" : function.trim();
        if (name.isEmpty()) {
            throw new ServiceException("未指定分析函数，已拒绝执行——可调用："
                    + String.join("、", callableFunctions()), BizErrorCode.VALIDATION_ERROR);
        }
        GenFunction registryFunction = GenFunction.byName(name);
        if (registryFunction == null) {
            throw new ServiceException("分析函数「" + name + "」不在函数注册表内——可调用："
                    + String.join("、", callableFunctions()), BizErrorCode.VALIDATION_ERROR);
        }
        if (!registryFunction.isAnalysis()) {
            throw new ServiceException("函数「" + name + "」不是只读分析函数（造数函数在数据工厂模板内"
                    + "生成参数值，不在此通道执行）——本通道可调用："
                    + String.join("、", callableFunctions()), BizErrorCode.VALIDATION_ERROR);
        }
        JsonNode filters = requireFilterObject(name, args);
        return switch (registryFunction) {
            case COVERAGE_SUMMARY -> readService.coverageSummary(projectId,
                    textFilter(filters, "chapter", name), textFilter(filters, "endType", name));
            case ASSERTION_DENSITY -> readService.assertionDensity(projectId,
                    textFilter(filters, "endType", name));
            case REHEARSAL_DRAFT -> rehearsalComposer.compose(projectId,
                    idFilter(filters, "suiteId", name), idFilter(filters, "planId", name));
            case REHEARSAL_RISK_REPORT -> riskReportComposer.compose(projectId,
                    idFilter(filters, "batchId", name));
            default -> throw new ServiceException("分析函数「" + name + "」尚未接通服务端取数，"
                    + "当前可调用：" + String.join("、", callableFunctions()),
                    BizErrorCode.VALIDATION_ERROR);
        };
    }

    /** Filters must be an object when present; absent or null = whole project. */
    private static JsonNode requireFilterObject(String name, JsonNode args) {
        if (args == null || args.isNull()) {
            return null;
        }
        if (!args.isObject()) {
            throw new ServiceException("分析函数「" + name + "」的入参需要 JSON 对象（如 "
                    + "{\"chapter\":\"登录\"}），当前形态无法解析", BizErrorCode.VALIDATION_ERROR);
        }
        Set<String> declared = DECLARED_ARGS.getOrDefault(name, Set.of());
        for (String key : new TreeSet<>(iterableKeys(args)) ) {
            if (!declared.contains(key)) {
                throw new ServiceException("分析函数「" + name + "」不接受参数 " + key
                        + "（忽略它会让结果变成项目全量口径，与提问范围不符）——该函数支持的参数："
                        + (declared.isEmpty() ? "无" : String.join("、", declared)),
                        BizErrorCode.VALIDATION_ERROR);
            }
        }
        return args;
    }

    private static Set<String> iterableKeys(JsonNode node) {
        Set<String> keys = new TreeSet<>();
        Iterator<String> fields = node.fieldNames();
        while (fields.hasNext()) {
            keys.add(fields.next());
        }
        return keys;
    }

    /** Text filter reader: absent/null/blank = no filter, non-text = refused. */
    private static String textFilter(JsonNode args, String key, String name) {
        if (args == null) {
            return null;
        }
        JsonNode node = args.get(key);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw new ServiceException("分析函数「" + name + "」的参数 " + key
                    + " 需要文本值（如 \"登录\"），当前无法作为筛选条件", BizErrorCode.VALIDATION_ERROR);
        }
        String value = node.asText().trim();
        return value.isEmpty() ? null : value;
    }

    /** Id filter reader: absent/null = unset, numeric (or integral text) = the id, anything else refused. */
    private static Long idFilter(JsonNode args, String key, String name) {
        if (args == null) {
            return null;
        }
        JsonNode node = args.get(key);
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isIntegralNumber()) {
            return node.longValue();
        }
        if (node.isTextual()) {
            try {
                return Long.parseLong(node.asText().trim());
            } catch (NumberFormatException ignored) {
                throw new ServiceException("分析函数「" + name + "」的参数 " + key
                        + " 需要整数 ID（如 77），当前值「" + node.asText() + "」无法作为编号",
                        BizErrorCode.VALIDATION_ERROR);
            }
        }
        throw new ServiceException("分析函数「" + name + "」的参数 " + key
                + " 需要整数 ID（如 77），当前形态无法解析", BizErrorCode.VALIDATION_ERROR);
    }

    /** Callable set plus its filter vocabulary, read straight off the registry. */
    private static Map<String, Set<String>> declaredArgs() {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        for (GenFunction candidate : GenFunction.values()) {
            if (!candidate.isAnalysis()) {
                continue;
            }
            out.put(candidate.functionName(), switch (candidate) {
                case COVERAGE_SUMMARY -> Set.of("chapter", "endType");
                case ASSERTION_DENSITY -> Set.of("endType");
                case REHEARSAL_DRAFT -> Set.of("suiteId", "planId");
                case REHEARSAL_RISK_REPORT -> Set.of("batchId");
                default -> Set.of();
            });
        }
        return Map.copyOf(out);
    }
}
