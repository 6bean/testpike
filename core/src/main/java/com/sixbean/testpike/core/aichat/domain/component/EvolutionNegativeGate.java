package com.sixbean.testpike.core.aichat.domain.component;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;

import java.util.List;
import java.util.Optional;

/**
 * Server-side single point of the four-class evolution negative list (AF-i,
 * AO ruling section 5): the self-evolution proposal generators run EVERY
 * candidate through {@link #requireAllowed} before assembling a draft card,
 * so a proposal that touches a forbidden surface never reaches the
 * confirmation door at all (assembly-time refusal, not confirm-time).
 * <p>Fail-closed by construction: the rule table is code, not configuration
 * — no K8 key, feature gate or role can open it, and there is no escape
 * hatch parameter. The four forbidden classes are:
 * <ul>
 *   <li>SELF_CODE_EDIT — rewriting the platform's own service code or build
 *       artefacts (the loop improves prompts/tools/grounding, never itself);</li>
 *   <li>GATE_OR_KEY_EDIT — writing configuration/K8 keys, i.e. moving the
 *       goalposts the human operators set;</li>
 *   <li>CONFIRM_SIGN_ON_BEHALF — auto-signing or bypassing the human
 *       confirmation ticket (red line: no write lands without a human);</li>
 *   <li>AUTO_PUBLISH — publishing or releasing without the review gate.</li>
 * </ul>
 * <p>One list, two consumers: the tool-coverage scanner reads the same
 * patterns when it audits tool surfaces; {@code EvolutionNegativeGateTest}
 * parses both sides and fails on drift, keeping them single-sourced.
 */
public final class EvolutionNegativeGate {

    /** Rule table, ordered exactly as the coverage gate's word list. */
    private static final List<EvolutionNegativeRule> RULES = List.of(
            new EvolutionNegativeRule("SELF_CODE_EDIT",
                    "(service\\.rewrite|source\\.edit|build\\.modify|jar\\.|deploy\\.self)"),
            new EvolutionNegativeRule("GATE_OR_KEY_EDIT",
                    "(config\\.(set|write|delete)|k8\\.key|sysconfig\\.(set|update)|ai\\.(chat|ctx|gen)\\.[a-z-]+\\.(set))"),
            new EvolutionNegativeRule("CONFIRM_SIGN_ON_BEHALF",
                    "(confirm\\.(auto|sign|bypass)|token\\.(issue-self|mint)|approval\\.self)"),
            new EvolutionNegativeRule("AUTO_PUBLISH",
                    "(publish\\.auto|release\\.direct|approve\\.self)"));

    private EvolutionNegativeGate() {
    }

    /** The rule table (labels + patterns) the contract test drift-checks. */
    public static List<EvolutionNegativeRule> rules() {
        return RULES;
    }

    /**
     * Identify the forbidden class a single candidate touches.
     *
     * @param candidate surface/tool name proposed for the evolution draft
     * @return the label of the first matching forbidden class, empty = allowed
     */
    public static Optional<String> findViolation(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return Optional.empty();
        }
        return RULES.stream()
                .filter(rule -> rule.hits(candidate))
                .map(EvolutionNegativeRule::label)
                .findFirst();
    }

    /**
     * Assembly-time hard refusal for a batch of candidates: the first
     * offender stops the generator with a plain-word error naming the
     * candidate and the class, so no draft card can be emitted for it.
     */
    public static void requireAllowed(List<String> candidates) {
        for (String candidate : candidates) {
            findViolation(candidate).ifPresent(label -> {
                throw new ServiceException(
                        "自进化提案被拒绝：" + candidate + " 属于禁止项（" + label
                                + "：进化环不能改平台自身代码、不能改配置闸与键值、不能代签人工确认、不能跳过评审自动发布）",
                        BizErrorCode.VALIDATION_ERROR);
            });
        }
    }
}
