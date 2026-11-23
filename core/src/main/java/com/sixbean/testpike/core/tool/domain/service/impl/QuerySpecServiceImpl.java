package com.sixbean.testpike.core.tool.domain.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.tool.api.dto.QueryAggregation;
import com.sixbean.testpike.core.tool.api.dto.QueryResult;
import com.sixbean.testpike.core.tool.api.dto.QuerySpec;
import com.sixbean.testpike.core.tool.domain.component.QueryTargetRegistry;
import com.sixbean.testpike.core.tool.domain.service.QuerySpecService;
import com.sixbean.testpike.core.tool.infrastructure.dataobject.QueryTargetRow;
import com.sixbean.testpike.core.tool.domain.component.ToolAccessGate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * QuerySpec interpreter: compiles the controlled DSL into one named-parameter
 * SELECT over a registered read-only target (AO batch P3 ,
 * mechanism for the ruling "the model only composes intent — the server
 * computes the numbers, zero raw SQL and zero reflection surface").
 * <p>Four hard boundaries, all fail-closed at compile time before any
 * statement runs (contract = QuerySpecAggregateBoundaryTest):
 * <ol>
 * <li>callable set = tp_query_target registry rows only — an unregistered
 * target, column, or filter field is refused by name;</li>
 * <li>no write primitives — any key outside the DSL shape (a smuggled
 * {@code deleteAll}, an unknown aggregation function) is refused, never
 * silently dropped, because a smuggled key is an event worth naming;</li>
 * <li>no arbitrary joins — the grammar is single-target; a {@code join} key
 * is an unknown field and dies at boundary 2;</li>
 * <li>no implicit cross-project reach — the scope column is server-forced
 * into WHERE and is not model-filterable (project_id is not a
 * registered dimension), and the caller's project visibility re-checks
 * through the single {@link ToolAccessGate} (same judgement component
 * as tool invoke — no second gate).</li>
 * </ol>
 * <p>Identifier hygiene: every column that reaches the SQL string is either a
 * registry value or a DSL value validated against the registry allow list, and
 * a final {@link #SAFE_IDENTIFIER} re-check — values always bind as named
 * parameters. The registry's hard_filter (e.g. soft-delete exclusion) is
 * ANDed server-side; it is registry content, never model-supplied.
 * <p>Row cap: limit is clamped to the target's max_rows so a card can never
 * ask for an unbounded scan (display-scale rule for live cards).
 */
@Service
public class QuerySpecServiceImpl implements QuerySpecService {

    /** The whole accepted key set of the DSL — anything else is refused. */
    private static final Set<String> DSL_KEYS =
            Set.of("target", "filters", "groupBy", "aggregations", "orderBy", "limit");

    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[a-z][a-z0-9_]{0,63}");

    /** hard_filter guardrail: registry content must stay a plain predicate. */
    private static final Pattern SAFE_HARD_FILTER =
            Pattern.compile("[A-Za-z0-9_ =<>!()'.,\\s]+");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final QueryTargetRegistry registry;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final ToolAccessGate accessGate;

    public QuerySpecServiceImpl(QueryTargetRegistry registry,
                                NamedParameterJdbcTemplate namedJdbc,
                                ToolAccessGate accessGate) {
        this.registry = registry;
        this.namedJdbc = namedJdbc;
        this.accessGate = accessGate;
    }

    @Override
    public QueryResult run(QuerySpec spec, long projectId, Long actorId) {
        // Single compile path: re-serialise the record and interpret the raw
        // JSON, so the unknown-key funnel guards both entrances identically.
        try {
            return runJson(MAPPER.writeValueAsString(spec), projectId, actorId);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new ServiceException("查询规格无法序列化，已拒绝执行——请在对话中改述查询需求重新发起，"
                    + "格式：{\"target\":\"test_case\",\"groupBy\":[\"status\"]}", BizErrorCode.VALIDATION_ERROR);
        }
    }

    @Override
    public QueryResult runJson(String rawJson, long projectId, Long actorId) {
        accessGate.requireProjectVisible(projectId, actorId);
        JsonNode root = parse(rawJson);
        refuseUnknownKeys(root);

        String targetKey = text(root.get("target"));
        QueryTargetRow target = registry.find(targetKey).orElseThrow(() ->
                new ServiceException("只读查询目标「" + targetKey + "」尚未登记——"
                        + "请改问已开放的统计目标（如「test_case」「exec_batch」「defect」），"
                        + "或在对话中重新描述要统计的内容", BizErrorCode.VALIDATION_ERROR));

        Set<String> dims = splitRegistryList(target.dimensionColumns());
        Set<String> measures = splitRegistryList(target.measureColumns());

        JsonNode filters = arrayNode(root.get("filters"), "filters");
        List<String> whereParts = new ArrayList<>();
        MapSqlParameterSource params = new MapSqlParameterSource();
        whereParts.add(quote(requireIdentifier(target.scopeColumn(), "登记表 scope 列")) + " = :__scope");
        params.addValue("__scope", projectId);
        appendHardFilter(target, whereParts);
        int idx = 0;
        for (JsonNode f : filters) {
            if (!f.isObject()) {
                throw new ServiceException("filters 每个条件必须是含 field 与 value 的对象——"
                        + "请改为格式：{\"field\":\"status\",\"value\":\"passed\"}"
                        + "（field 取自该目标登记的维度列）", BizErrorCode.VALIDATION_ERROR);
            }
            String field = text(f.get("field"));
            if (!SAFE_IDENTIFIER.matcher(field).matches() || !dims.contains(field)) {
                throw new ServiceException("过滤字段「" + field + "」不是目标「" + targetKey
                        + "」登记的维度列——该目标登记的维度列为 " + String.join("、", dims)
                        + "，请从中选择一个重写条件，示例：\"field\":\"status\"",
                        BizErrorCode.VALIDATION_ERROR);
            }
            JsonNode value = f.get("value");
            if (value == null || !value.isValueNode()) {
                throw new ServiceException("过滤字段「" + field + "」的值必须是标量（字符串/数字/布尔）——"
                        + "请改为形如「passed」或 3 或 true 的单值，不要写成数组或对象",
                        BizErrorCode.VALIDATION_ERROR);
            }
            whereParts.add(quote(field) + " = :f" + idx);
            params.addValue("f" + idx, scalarOf(value));
            idx++;
        }

        List<String> groupBy = new ArrayList<>();
        for (JsonNode g : arrayNode(root.get("groupBy"), "groupBy")) {
            String col = text(g);
            if (!SAFE_IDENTIFIER.matcher(col).matches() || !dims.contains(col)) {
                throw new ServiceException("分组列「" + col + "」不是目标「" + targetKey
                        + "」登记的维度列——该目标登记的维度列为 " + String.join("、", dims)
                        + "，请从中选择一个，示例：\"groupBy\":[\"status\"]",
                        BizErrorCode.VALIDATION_ERROR);
            }
            if (!groupBy.contains(col)) {
                groupBy.add(col);
            }
        }

        // label -> "FUNC(col)" select expression, order preserved.
        LinkedHashMap<String, String> aggSelects = new LinkedHashMap<>();
        JsonNode aggs = arrayNode(root.get("aggregations"), "aggregations");
        for (JsonNode a : aggs) {
            if (!a.isObject()) {
                throw new ServiceException("aggregations 每项必须是含 function 与 column 的对象——"
                        + "请改为格式：{\"function\":\"count\",\"column\":\"\"}",
                        BizErrorCode.VALIDATION_ERROR);
            }
            String fn = text(a.get("function"));
            if (fn == null || !QueryAggregation.FUNCTIONS.contains(fn)) {
                throw new ServiceException("聚合函数「" + fn + "」不在封闭词表内——"
                        + "请改为「count/sum/avg/min/max」之一（只读代理不提供其它计算）",
                        BizErrorCode.VALIDATION_ERROR);
            }
            String col = text(a.get("column"));
            String label;
            String expr;
            if (col == null || col.isBlank()) {
                if (!"count".equals(fn)) {
                    throw new ServiceException("聚合「" + fn + "」必须指定列名——"
                            + "请补 column 字段，示例：{\"function\":\"avg\",\"column\":\"priority\"}"
                            + "（仅 count 可省略列名）", BizErrorCode.VALIDATION_ERROR);
                }
                label = "count";
                expr = "count(*)";
            } else {
                if (!SAFE_IDENTIFIER.matcher(col).matches() || !measures.contains(col)) {
                    throw new ServiceException("聚合列「" + col + "」不是目标「" + targetKey
                            + "」登记的度量列——该目标登记的度量列为 "
                            + (measures.isEmpty() ? "（无，此目标仅支持 count 计数）" : String.join("、", measures))
                            + "，请从中选择，示例：{\"function\":\"sum\",\"column\":\"review_fail_cnt\"}",
                            BizErrorCode.VALIDATION_ERROR);
                }
                label = fn + "_" + col;
                expr = fn + "(" + quote(col) + ")";
            }
            if (aggSelects.containsKey(label)) {
                throw new ServiceException("聚合「" + label + "」重复——请删掉其中一条，"
                        + "或改为对不同的度量列分别聚合（如「review_fail_cnt」求和与「confidence」求平均）",
                        BizErrorCode.VALIDATION_ERROR);
            }
            aggSelects.put(label, expr);
        }
        if (aggSelects.isEmpty()) {
            aggSelects.put("count", "count(*)");
        }
        if (groupBy.isEmpty() && aggSelects.isEmpty()) {
            throw new ServiceException("查询规格至少需要 groupBy 或 aggregations 之一——"
                    + "请补一项，示例：\"groupBy\":[\"status\"]",
                    BizErrorCode.VALIDATION_ERROR);
        }

        List<String> columns = new ArrayList<>(groupBy);
        columns.addAll(aggSelects.keySet());

        StringBuilder sql = new StringBuilder("SELECT ");
        List<String> selects = new ArrayList<>();
        for (String col : groupBy) {
            selects.add(quote(col));
        }
        aggSelects.forEach((label, expr) -> selects.add(expr + " AS " + quote(label)));
        sql.append(String.join(", ", selects));
        sql.append(" FROM ").append(quote(requireIdentifier(target.baseTable(), "登记表 base 表")));
        sql.append(" WHERE ").append(String.join(" AND ", whereParts));
        if (!groupBy.isEmpty()) {
            sql.append(" GROUP BY ")
                    .append(String.join(", ", groupBy.stream().map(QuerySpecServiceImpl::quote).toList()));
        }
        appendOrderBy(root.get("orderBy"), columns, sql, targetKey);

        int effectiveLimit = resolveLimit(root.get("limit"), target.maxRows());
        sql.append(" LIMIT :__limit");
        params.addValue("__limit", effectiveLimit);

        List<Map<String, Object>> rows = namedJdbc.queryForList(sql.toString(), params);
        return new QueryResult(targetKey, columns, rows, effectiveLimit);
    }

    // ---- compile-time guards ------------------------------------------------

    private static void appendHardFilter(QueryTargetRow target, List<String> whereParts) {
        String hard = target.hardFilter();
        if (hard == null || hard.isBlank()) {
            return;
        }
        if (!SAFE_HARD_FILTER.matcher(hard).matches()) {
            throw new ServiceException("只读查询目标「" + target.targetKey()
                    + "」登记的 hard_filter 含非法字符，该目标已停用——"
                    + "该谓词由运维在数据表 tp_query_target 的「hard_filter」列维护，请运维核对该行登记值后重试",
                    BizErrorCode.VALIDATION_ERROR);
        }
        whereParts.add("(" + hard + ")");
    }

    private static void appendOrderBy(JsonNode orderBy, List<String> columns,
                                      StringBuilder sql, String targetKey) {
        if (orderBy == null || orderBy.isNull()) {
            return;
        }
        if (!orderBy.isArray()) {
            throw new ServiceException("orderBy 必须是数组——请改为形如 [\"status:asc\"] 的数组，"
                    + "元素为「列名:asc」或「列名:desc」", BizErrorCode.VALIDATION_ERROR);
        }
        List<String> clauses = new ArrayList<>();
        for (JsonNode o : orderBy) {
            String spec = text(o);
            String col = spec == null ? "" : spec.split(":", 2)[0].trim();
            String dir = spec != null && spec.contains(":") ? spec.split(":", 2)[1].trim() : "asc";
            if (!columns.contains(col)) {
                throw new ServiceException("排序列「" + col + "」不在目标「" + targetKey
                        + "」的结果列内——请改为本次查询的结果列之一："
                        + String.join("、", columns)
                        + "（只能按已选出的分组列或聚合label排序，如「status」）",
                        BizErrorCode.VALIDATION_ERROR);
            }
            if (!dir.equalsIgnoreCase("asc") && !dir.equalsIgnoreCase("desc")) {
                throw new ServiceException("排序方向「" + dir + "」非法——请改为「asc」或「desc」，"
                        + "如 \"status:desc\"", BizErrorCode.VALIDATION_ERROR);
            }
            clauses.add(quote(col) + " " + dir.toUpperCase());
        }
        if (!clauses.isEmpty()) {
            sql.append(" ORDER BY ").append(String.join(", ", clauses));
        }
    }

    private static int resolveLimit(JsonNode limit, int maxRows) {
        if (limit == null || limit.isNull()) {
            return maxRows;
        }
        if (!limit.isIntegralNumber() || limit.asInt() < 1) {
            throw new ServiceException("limit 必须是 ≥1 的整数——请改为正整数（如「50」），"
                    + "或删除 limit 由服务端按该目标登记的最大行数返回", BizErrorCode.VALIDATION_ERROR);
        }
        return Math.min(limit.asInt(), maxRows);
    }

    private static void refuseUnknownKeys(JsonNode root) {
        List<String> offenders = new ArrayList<>();
        root.fieldNames().forEachRemaining(name -> {
            if (!DSL_KEYS.contains(name)) {
                offenders.add(name);
            }
        });
        if (!offenders.isEmpty()) {
            throw new ServiceException("查询规格包含不存在的字段 " + String.join("、", offenders)
                    + "——只读 DSL 仅支持「target」「filters」「groupBy」「aggregations」「orderBy」「limit」六个键，"
                    + "请删去越界字段后重试（无写原语、无 join、无表名直达）",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    private static JsonNode parse(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            throw new ServiceException("查询规格不能为空——请在对话中说明要统计的内容"
                    + "（如「按状态统计用例数」），格式：{\"target\":\"test_case\",\"groupBy\":[\"status\"]}",
                    BizErrorCode.VALIDATION_ERROR);
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(rawJson);
        } catch (Exception e) {
            throw new ServiceException("查询规格不是合法 JSON，已拒绝执行——请改为合法 JSON 对象后重试，"
                    + "格式：{\"target\":\"test_case\",\"groupBy\":[\"status\"]}", BizErrorCode.VALIDATION_ERROR);
        }
        if (!root.isObject()) {
            throw new ServiceException("查询规格必须是 JSON 对象——请改为格式："
                    + "{\"target\":\"test_case\",\"groupBy\":[\"status\"]}（对象而非数组或标量）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return root;
    }

    // ---- small shared helpers ------------------------------------------------

    private static Set<String> splitRegistryList(String csv) {
        Set<String> out = new LinkedHashSet<>();
        if (csv != null) {
            for (String part : csv.split(",")) {
                if (!part.isBlank()) {
                    out.add(part.trim());
                }
            }
        }
        return out;
    }

    private static String requireIdentifier(String value, String what) {
        if (value == null || !SAFE_IDENTIFIER.matcher(value).matches()) {
            throw new ServiceException(what + "「" + value + "」不是合法标识符，登记表数据异常——"
                    + "请运维核对数据表 tp_query_target 该目标行的「base_table」「scope_column」登记值"
                    + "（须为小写字母开头的表/列名）", BizErrorCode.VALIDATION_ERROR);
        }
        return value;
    }

    private static JsonNode arrayNode(JsonNode node, String key) {
        if (node == null || node.isNull()) {
            return MAPPER.createArrayNode();
        }
        if (!node.isArray()) {
            throw new ServiceException(key + " 必须是数组——请改为格式：[\"status\"]"
                    + "（无此项时请整体删除该键）", BizErrorCode.VALIDATION_ERROR);
        }
        return node;
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    private static Object scalarOf(JsonNode value) {
        if (value.isNumber()) {
            return value.numberValue();
        }
        if (value.isBoolean()) {
            return value.booleanValue();
        }
        return value.asText();
    }

    private static String quote(String identifier) {
        return "\"" + identifier + "\"";
    }

    /**
     * Registry rows projected onto the model-facing view (physical table,
     * scope column and hard filter withheld — the interpreter enforces them
     * server-side and the model composing specs never needs them).
     */
    @Override
    public List<com.sixbean.testpike.core.tool.api.dto.QueryTargetView> callableTargets() {
        return registry.findAll().stream()
                .map(row -> new com.sixbean.testpike.core.tool.api.dto.QueryTargetView(
                        row.targetKey(), row.dimensionColumns(),
                        row.measureColumns(), row.maxRows()))
                .toList();
    }
}
