package com.sixbean.testpike.core.perf.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.util.NonUiActionContract;
import org.springframework.stereotype.Component;

/**
 * Compile-stage step gate for perf scenarios (shared component; red line 6:
 * both engine adapters consume this single source, no second enum copy).
 * <p>A-11 narrowing: the whitelist collapses from the whole non-UI action set
 * to {@code http.request} alone — the k6/Gatling templates only fill the
 * method/url slots, so any other action that passed the old gate compiled into
 * an empty-URL call (fake traffic). Login stays expressed as an auth-flagged
 * http.request step under the scenario-level D4 strategy, not a separate
 * action, so the narrowed set needs no LOGIN member. The save gate in
 * {@code PerfScenarioServiceImpl} rejects through {@link #violationFor(String)}
 * as well — one judgement and one message corpus for both stages (defense in
 * depth against views constructed without passing the save gate).
 * <p> A-3: the field domain of an approved http.request step (expect
 * keys, saveAs identifier, poll shape, method enum) is judged by the shared
 * 04-contract validator {@link NonUiActionContract#httpRequestViolation} —
 * perf reuses the legal domain verbatim, no second spec. A mistyped assertion
 * key that silently no-ops would let broken expectations ride under load, so
 * unknown keys fail closed here too.
 */
@Component
public class PerfStepActionGate {

    /** The only action the perf templates can compile (A-11). */
    public static final String ALLOWED_ACTION = "http.request";

    /**
     * Plain-word verdict for one step action: null = allowed; any other action —
     * browser UI or the remaining non-UI primitives alike — names the offender,
     * the single allowed action, and the reason (templates fill method/url only).
     */
    public static String violationFor(String action) {
        if (action != null && !action.isBlank() && ALLOWED_ACTION.equals(action)) {
            return null;
        }
        return "压测步骤动作「" + action + "」不可用：压测场景仅支持 " + ALLOWED_ACTION
                + "（压力脚本模板只编译 method/url 两槽，其余动作会被编成空地址假流量）。"
                + "登录不发独立动作——写成 http.request 步骤即可，登录频率由场景级登录策略控制；"
                + "SQL/浏览器断言类请走功能用例";
    }

    /**
 * B-3 mix keys (transaction/weight) save-side structural verdict (single
 * source, whole step array): a mix declaration must be internally complete
 * or the stored promise is one the settings face cannot keep. Rules:
 * a {@code weight} on a step without a {@code transaction} is orphaned;
 * a mix that declares {@code transaction} on only some steps leaves the
 * flat ones ambiguous (join a group or ride an unweighted leg — guessing
 * is what the fail-closed family forbids); a weight must be a positive
 * number; two steps of one transaction must not declare different weights.
 * Violations name the offending step number. The group derivation
 * (PerfMixGroups) lands with the engine mapping segment. Engine capability
 * is NOT judged here — the compile stage answers per engine (same split as
 * the SHARED login-strategy refusal: save stores the declaration honestly,
 * compile owns the capability answer).
 */
    public static String mixStructureViolation(JsonNode steps) {
        boolean anyTx = false;
        for (JsonNode step : steps) {
            anyTx |= !step.path("transaction").asText("").isBlank();
        }
        int idx = 0;
        for (JsonNode step : steps) {
            idx++;
            JsonNode weight = step.get("weight");
            boolean hasWeight = weight != null && !weight.isNull();
            boolean tx = !step.path("transaction").asText("").isBlank();
            if (hasWeight && !tx) {
                return "第 " + idx + " 步含 weight 配比键但没有 transaction 分组：权重必须"
                        + "随链路整步申报（孤立的 weight 无消费方，编入链路会失真）";
            }
            if (hasWeight && (!weight.isNumber() || weight.asInt() <= 0)) {
                return "第 " + idx + " 步 weight 必须是正整数配比（当前值「" + weight.asText()
                        + "」）：0/负数/非数字都会把链路配比折算成不存在的流量";
            }
            if (anyTx && !tx) {
                return "压测链路配比必须整场申报：第 " + idx
                        + " 步没有 transaction（半声明无法判定该步是并入链路还是独立施压——"
                        + "请为每个步骤声明 transaction，或全部摘掉配比键）";
            }
        }
        if (!anyTx) {
            return null;
        }
        // Same group must not carry two different weights (one link, two
        // ratios — the compiler cannot serve both).
        for (int i = 0; i < steps.size(); i++) {
            JsonNode a = steps.get(i);
            for (int j = i + 1; j < steps.size(); j++) {
                JsonNode b = steps.get(j);
                if (a.path("transaction").asText("").equals(b.path("transaction").asText(""))
                        && a.hasNonNull("weight") && b.hasNonNull("weight")
                        && a.get("weight").asDouble() != b.get("weight").asDouble()) {
                    return "同一压测链路「" + a.path("transaction").asText() + "」内声明了不同权重（"
                            + a.get("weight").asText() + " 与 " + b.get("weight").asText()
                            + "）：一条链路只有一个配比，请整组统一";
                }
            }
        }
        return null;
    }

    /**
     * Compile-stage capability answer for the mix shape (per engine): a
     * scenario declaring transaction links can only compile where the template
     * actually renders the ratio. {@code mixSupported} = this engine consumes
     * the mix (both engines landed: k6 weighted multi-block = stage c-2β,
     * Gatling one-population-per-link = c-2γ) — compiling a mix on an engine
     * that ignores it would report a uniform run as a mixed-link one (fake mix).
     */
    public static String mixCapabilityViolation(JsonNode steps, boolean mixSupported) {
        if (mixSupported) {
            return null;
        }
        for (JsonNode step : steps) {
            if (!step.path("transaction").asText("").isBlank()) {
                return "该引擎的压力模板尚未接入全链路配比（transaction/weight）编译：声明的"
                        + "混合比例会被编成均匀流量=假配比——该映射切片落地前请拆成"
                        + "独立场景分别施压（每链路一个场景）";
            }
        }
        return null;
    }

    /**
 * Full verdict for one perf step : action whitelist, then the
 * http.request field domain from the shared 04 contract (expect/saveAs/poll/
 * method) plus the perf-specific blank-url guard — an empty URL compiles
 * into a fake call against the base address. Null = the step is legal.
 * <p>B-3 mix keys ride {@link #mixStructureViolation} (whole-array
 * judgement — a half-declared mix is only visible across steps), not here.
 */
    public String stepViolation(JsonNode step) {
        String actionViolation = violationFor(step.path("action").asText(""));
        if (actionViolation != null) {
            return actionViolation;
        }
        if (step.path("url").asText("").isBlank()) {
            return "压测步骤还差目标地址（url）：留空时只会访问环境根地址，测不到真实接口负载——"
                    + "请在该步骤填写完整接口地址，支持 {{ENV:}} 占位符，例 {{ENV:BASE}}/orders";
        }
        return NonUiActionContract.httpRequestViolation(step);
    }

    /**
 * Compile-stage construct check (load-template face): a field that is legal in
 * the 04 contract but inexpressible in a pressure script must fail closed here
 * — silently dropping a poll wait or a variable capture would fake a green run
 * whose traffic does not match the declared step. Kept separate from
 * {@link #stepViolation} because the save gate intentionally accepts the full
 * contract domain (the same step is runnable in the functional case runner).
 * <p>{@code varChainSupported} = this engine template actually consumes
 * saveAs/{{VAR:} (both k6 and Gatling consume it since DSL v2 — an
 * engine that could not consume would still have to reject instead of
 * silently dropping the capture.
 */
    public static String loadTemplateUnsupportedViolation(JsonNode step, boolean varChainSupported) {
        if (step.has("poll") && !step.get("poll").isNull()) {
            return "压测步骤含 poll 轮询等待：压力脚本每次迭代单发请求，模板无法编译轮询语义"
                    + "（轮询等异步终态请走功能用例，压测目标应为其已稳态的接口）";
        }
        if (!varChainSupported && step.has("saveAs") && !step.get("saveAs").isNull()) {
            return "压测步骤含 saveAs 变量捕获：该引擎的压力模板尚未接入变量链"
                    + "（捕获值在压力迭代间无消费方，静默丢弃等于假流量；请改用 k6 或等待该引擎变量链切片）";
        }
        JsonNode expect = step.get("expect");
        if (expect != null && expect.isObject()) {
            if (expect.has("durationMs") && !expect.get("durationMs").isNull()) {
                return "压测步骤 expect.durationMs 耗时断言暂不可编译进压力模板"
                        + "（耗时门限由 SLA 判定在控制面统一把关，步骤级重复断言=两套判定）";
            }
            if (expect.has("headers") && !expect.get("headers").isNull()) {
                return "压测步骤 expect.headers 响应头断言暂不可编译进压力模板"
                        + "（静默丢弃等于断言从未生效；响应头校验请走功能用例）";
            }
            JsonNode bodyRows = expect.get("body");
            if (bodyRows != null && bodyRows.isArray()) {
                for (JsonNode row : bodyRows) {
                    String matchType = row.path("matchType").asText("contains");
                    if (!matchType.equals("contains") && !matchType.equals("exact")) {
                        return "压测步骤 expect.body 行 matchType「" + matchType
                                + "」暂不可编译进压力模板（本切片仅支持 contains/exact，"
                                + "range/regex 行为执行器匹配器族同源扩展随后续切片接入两引擎）";
                    }
                }
            }
        }
        return null;
    }

    /**
     * Compile-stage rejection (load-template face): per-step field domain plus
     * the B-3 mix capability answer for THIS engine — {@code mixSupported}
     * declares whether the engine template consumes the mix (k6 weighted
     * multi-block = stage c-2β; Gatling exec-split legs = c-2γ). A mix compiled
     * on an engine that ignores it would report uniform pressure as a
     * mixed-link run, so the refusal is honest and names the workaround.
     */
    public void rejectUiActions(JsonNode steps, boolean varChainSupported, boolean mixSupported) {
        String mixCapability = mixCapabilityViolation(steps, mixSupported);
        if (mixCapability != null) {
            throw new ServiceException(mixCapability, BizErrorCode.VALIDATION_ERROR);
        }
        for (JsonNode step : steps) {
            String violation = stepViolation(step);
            if (violation != null) {
                throw new ServiceException(violation, BizErrorCode.VALIDATION_ERROR);
            }
            String unsupported = loadTemplateUnsupportedViolation(step, varChainSupported);
            if (unsupported != null) {
                throw new ServiceException(unsupported, BizErrorCode.VALIDATION_ERROR);
            }
        }
    }
}
