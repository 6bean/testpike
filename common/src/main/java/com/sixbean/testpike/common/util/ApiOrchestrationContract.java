package com.sixbean.testpike.common.util;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Orchestration vocabulary contract (single source for request-level precondition
 * and grouped-loop legality in the api-test envelope).
 * <p> maps the case-domain control-flow words (04 contract loop/if)
 * onto the apitest form grammar: a precondition row decides whether a request
 * runs or is honestly skipped; a loop row decides the repeat policy around it.
 * <p>Mirror consumers, never a second verdict copy: the core save gate
 * (ApiRequestServiceImpl) validates stored rows, the front-end builder mirrors
 * the same enums, and the executor advances the plan — all read this class.
 * Matcher sub-rows delegate to {@link ExpectMatchContract} and the variable
 * name shape stays on the pool's key pattern family (red line 6).
 * <p>Pure static and stateless: violations are plain-language verdict strings
 * (null = legal); error-code wrapping belongs to the caller.
 */
public final class ApiOrchestrationContract {

    /** Precondition field domain: last response status or a pool variable. */
    public static final Set<String> PRECONDITION_FIELDS = Set.of("status", "var");

    /** Comparison operators (shared by status and var equality forms). */
    public static final Set<String> COMPARE_OPS = Set.of("eq", "ne");

    /** Existence operators (var-only: the pool either holds the key or not). */
    public static final Set<String> EXISTENCE_OPS = Set.of("exists", "absent");

    /** Pattern-match operator, delegating its grammar to ExpectMatchContract. */
    public static final String MATCH_OP = "matches";

    /** Set-membership operator (status-only, expect = non-empty integer array). */
    public static final String IN_OP = "in";

    /** Loop mode domain: fixed count or repeat-until (case-domain 04 mapping). */
    public static final Set<String> LOOP_MODES = Set.of("count", "until");

    /** Upper bound for a count loop (a runaway repeat is refused at save time). */
    public static final int LOOP_TIMES_MAX = 100;

    /** Upper bound for the until stop guard. */
    public static final int LOOP_MAX_TIMES_BOUND = 100;

    /** Inter-iteration delay ceiling (ms) — longer waits belong in the scheduler, not a loop. */
    public static final int LOOP_DELAY_MS_MAX = 3_600_000;

    /** Pool variable names: dotted identifier path, same shape family as saveAs keys. */
    private static final Pattern VAR_NAME = Pattern.compile("[A-Za-z_]\\w*(\\.[A-Za-z_]\\w*)*");

    private ApiOrchestrationContract() {
        // static contract, no instances
    }

    /**
     * Precondition row verdict (null = legal; missing/empty object = no gate).
     * <p>status: eq/ne take one 100–599 integer, in takes a non-empty integer array.
     * var: exists/absent need only a well-formed name; eq/ne add expect;
     * matches adds expect + matchType validated by ExpectMatchContract.
     */
    public static String preconditionViolation(JsonNode row) {
        if (row == null || row.isNull()) {
            return null;
        }
        if (!row.isObject()) {
            // Scalars (e.g. the string "status==200") must not read as "no gate":
            // JsonNode.isEmpty() is size()==0, which is true for every TextNode.
            // An empty object {} legitimately means "no precondition" (fixture pins both).
            return "前置条件必须是一个表单对象（field/op/expect），当前不是对象形状";
        }
        if (row.isEmpty()) {
            return null;
        }
        String field = row.path("field").asText("");
        if (!PRECONDITION_FIELDS.contains(field)) {
            return "前置条件字段仅支持 " + String.join(" / ", sorted(PRECONDITION_FIELDS))
                    + "（上一跳状态码 / 变量池键名），当前值「" + field + "」";
        }
        return "status".equals(field)
                ? statusPreconditionViolation(row)
                : varPreconditionViolation(row);
    }

    private static String statusPreconditionViolation(JsonNode row) {
        String op = row.path("op").asText("");
        if (!COMPARE_OPS.contains(op) && !IN_OP.equals(op)) {
            return "状态码前置条件仅支持 eq / ne / in，当前值「" + op + "」";
        }
        JsonNode expect = row.get("expect");
        if (IN_OP.equals(op)) {
            if (expect == null || !expect.isArray() || expect.isEmpty()) {
                return "状态码 in 前置条件的 expect 必须是非空整数数组，例 [200, 201]";
            }
            for (JsonNode item : expect) {
                String range = httpStatusRange(item);
                if (range != null) {
                    return range;
                }
            }
            return null;
        }
        return httpStatusRange(expect);
    }

    private static String varPreconditionViolation(JsonNode row) {
        String name = row.path("name").asText("");
        if (name.isBlank() || !VAR_NAME.matcher(name).matches()) {
            return "变量前置条件的 name 必须是点分标识符（例 token 或 order.state），当前值「" + name + "」";
        }
        String op = row.path("op").asText("");
        if (EXISTENCE_OPS.contains(op)) {
            return null;
        }
        if (COMPARE_OPS.contains(op)) {
            JsonNode expect = row.get("expect");
            if (expect == null || expect.isNull()) {
                return "变量 " + op + " 前置条件必须给出 expect 比较值";
            }
            return null;
        }
        if (MATCH_OP.equals(op)) {
            JsonNode expect = row.get("expect");
            if (expect == null || expect.isNull() || !expect.isTextual()) {
                return "变量 matches 前置条件的 expect 必须是字符串模式值";
            }
            // Reuse the expect matcher grammar wholesale (contains/exact/regex/range
            // semantics live in ExpectMatchContract — no second regex policy here).
            return ExpectMatchContract.expectViolation(row);
        }
        return "变量前置条件仅支持 exists / absent / eq / ne / matches，当前值「" + op + "」";
    }

    /**
     * Loop row verdict (null = legal; missing/empty object = straight-line run).
     * <p>count: times within 1..LOOP_TIMES_MAX. until: maxTimes guard within
     * 1..bound plus a stop row that is itself a legal precondition row. Optional
     * delayMs (inter-iteration pause) must be 0..ceiling.
     */
    public static String loopViolation(JsonNode row) {
        if (row == null || row.isNull()) {
            return null;
        }
        if (!row.isObject()) {
            // Same shape rule as preconditionViolation: a scalar is a broken
            // envelope, not "no loop" (only {} legitimately means straight-line).
            return "循环配置必须是一个表单对象（mode/times/until），当前不是对象形状";
        }
        if (row.isEmpty()) {
            return null;
        }
        String mode = row.path("mode").asText("");
        if (!LOOP_MODES.contains(mode)) {
            return "循环模式仅支持 " + String.join(" / ", sorted(LOOP_MODES))
                    + "（固定次数 / 条件停止），当前值「" + mode + "」";
        }
        String delay = delayViolation(row);
        if (delay != null) {
            return delay;
        }
        if ("count".equals(mode)) {
            JsonNode times = row.get("times");
            if (times == null || !times.isIntegralNumber()) {
                return "固定次数循环必须给出整数 times（1~" + LOOP_TIMES_MAX + "）";
            }
            if (times.asInt() < 1 || times.asInt() > LOOP_TIMES_MAX) {
                return "固定次数循环 times 必须在 1~" + LOOP_TIMES_MAX
                        + " 之间，当前值「" + times.asInt() + "」";
            }
            return null;
        }
        JsonNode maxTimes = row.get("maxTimes");
        if (maxTimes == null || !maxTimes.isIntegralNumber()
                || maxTimes.asInt() < 1 || maxTimes.asInt() > LOOP_MAX_TIMES_BOUND) {
            return "条件停止循环必须给出整数 maxTimes 保护上限（1~" + LOOP_MAX_TIMES_BOUND
                    + "），防止永不满足时停不下来";
        }
        // The stop row is mandatory for an until loop: a missing/empty sub-row
        // would silently degrade to a fixed-count repeat at run time, and the
        // executor refuses that shape (fail-closed) — save gate same vocabulary.
        JsonNode until = row.get("until");
        if (until == null || until.isNull() || (until.isObject() && until.isEmpty())) {
            return "条件停止循环必须给出 until 停止条件行（field/op/expect 表单行）";
        }
        return preconditionViolation(until);
    }

    private static String delayViolation(JsonNode row) {
        JsonNode delay = row.get("delayMs");
        if (delay == null || delay.isNull()) {
            return null;
        }
        if (!delay.isIntegralNumber() || delay.asLong() < 0 || delay.asLong() > LOOP_DELAY_MS_MAX) {
            return "循环间隔 delayMs 必须是 0~" + LOOP_DELAY_MS_MAX + " 的整数毫秒";
        }
        return null;
    }

    private static String httpStatusRange(JsonNode value) {
        if (value == null || !value.isIntegralNumber()
                || value.asInt() < 100 || value.asInt() > 599) {
            return "状态码前置条件 expect 必须是 100–599 的整数，例 200";
        }
        return null;
    }

    private static String[] sorted(Set<String> options) {
        return options.stream().sorted().toArray(String[]::new);
    }
}
