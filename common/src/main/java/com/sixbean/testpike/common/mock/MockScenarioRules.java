package com.sixbean.testpike.common.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Shared stateful-scenario declaration kernel . One place
 * owns the machine shape — the save chain uses it to refuse broken machines
 * with plain words, the traffic plane later reads the same declared state set
 * to validate runtime advances (red line: no second dialect between the planes).
 * <p>Shape (frozen here): a JSON object with
 * <ul>
 * <li>{@code states} — non-empty array of state labels (first = default
 * initial when {@code initial} is absent);</li>
 * <li>{@code initial} — optional, must be one of {@code states};</li>
 * <li>{@code scope} — optional lane spectrum {@code session} (default) /
 * {@code role} / {@code global}; the X-TP-Mock-Session lane stays
 * session-level by default with role-level optional per the M-c ruling;</li>
 * <li>{@code transitions} — optional array of {@code {from,to}} pairs, both
 * endpoints must be declared states (documentation for the editor graph;
 * firing rules bind states through stub rows).</li>
 * </ul>
 * State labels are conservative tokens (letters/digits/underscore/hyphen/dot,
 * first alphanumeric, 1..80) because they travel inside Redis keys and the
 * inspector text — no whitespace surprises.
 */
@Component
public class MockScenarioRules {

    /** Lane spectra fixed by the M-c ruling: session default, role optional, global escape. */
    public static final List<String> SCOPES = List.of("session", "role", "global");

    /** Conservative state-label shape: it is spliced into Redis keys and traces. */
    private static final Pattern STATE_LABEL = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,79}");

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Parse + validate a declaration and return the declared state set in
     * declaration order. Everything illegal is a plain-word ServiceException
     * at the save chain (a machine that cannot be named cannot be trusted to
     * advance traffic).
     */
    public Set<String> declaredStates(String definitionJson) {
        JsonNode root;
        try {
            root = objectMapper.readTree(definitionJson == null ? "" : definitionJson.trim());
        } catch (Exception e) {
            throw new ServiceException("场景定义不是合法 JSON 对象（应含 states 数组）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return declaredStates(root);
    }

    /** Same validation over an already-parsed node (loader/traffic side reuses it). */
    public Set<String> declaredStates(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new ServiceException("场景定义必须是 JSON 对象（states/initial/scope/transitions 键）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        JsonNode states = root.path("states");
        if (!states.isArray() || states.isEmpty()) {
            throw new ServiceException("场景状态集合 states 必须是非空 JSON 数组——没有状态的机器无法步进",
                    BizErrorCode.VALIDATION_ERROR);
        }
        Set<String> declared = new LinkedHashSet<>();
        for (JsonNode s : states) {
            String label = s.isTextual() ? s.asText() : null;
            if (label == null || !STATE_LABEL.matcher(label).matches()) {
                throw new ServiceException("状态名只能用字母、数字、下划线、连字符和点，且以字母或数字开头"
                        + "（它会进入 Redis 键与检视轨迹）：「" + s.asText("?") + "」不符合",
                        BizErrorCode.VALIDATION_ERROR);
            }
            if (!declared.add(label)) {
                throw new ServiceException("状态「" + label + "」在 states 中重复声明",
                        BizErrorCode.VALIDATION_ERROR);
            }
        }
        String initial = root.path("initial").asText(null);
        if (initial != null && !declared.contains(initial)) {
            throw new ServiceException("初始状态「" + initial + "」不在已声明状态集合之内",
                    BizErrorCode.VALIDATION_ERROR);
        }
        String scope = root.path("scope").asText(null);
        if (scope != null && !SCOPES.contains(scope)) {
            throw new ServiceException("泳道范围不支持「" + scope + "」，可选值："
                    + String.join(" / ", SCOPES), BizErrorCode.VALIDATION_ERROR);
        }
        JsonNode transitions = root.path("transitions");
        if (!transitions.isMissingNode() && !transitions.isNull()) {
            if (!transitions.isArray()) {
                throw new ServiceException("transitions 必须是 JSON 数组（每项含 from/to）",
                        BizErrorCode.VALIDATION_ERROR);
            }
            List<String> edges = new ArrayList<>();
            for (JsonNode t : transitions) {
                String from = t.path("from").asText(null);
                String to = t.path("to").asText(null);
                edges.add(from + "->" + to);
                if (from == null || !declared.contains(from) || to == null || !declared.contains(to)) {
                    throw new ServiceException("转换端点必须落在已声明状态集合内：" + from + "->" + to,
                            BizErrorCode.VALIDATION_ERROR);
                }
            }
        }
        return declared;
    }

    /**
     * Validate that a state label used by a stub row belongs to the machine.
     * The traffic plane writes advances through the same check, so an
     * undeclared state can never surface in Redis either — out-of-range
     * advances are refused and named in the ledger.
     */
    public void requireDeclared(Set<String> declared, String state, String scenarioName, String column) {
        if (state == null || state.isBlank()) {
            return; // absent column = fires in any state
        }
        if (!declared.contains(state)) {
            throw new ServiceException("场景「" + scenarioName + "」未声明状态「" + state + "」（"
                    + column + "）——请先在场景定义 states 中声明该状态", BizErrorCode.VALIDATION_ERROR);
        }
    }
}
