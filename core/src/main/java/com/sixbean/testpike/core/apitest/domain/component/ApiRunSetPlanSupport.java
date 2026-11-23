package com.sixbean.testpike.core.apitest.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * Static planner for a pinned collection run (; dispatch-plan computation
 * only — never an assertion verdict, judgment stays in the executor kernel).
 * <p>Consumes the run-set pinned definition JSON (array of request snapshots in
 * tree order) and answers: who extracts which pool key, who references it, and
 * which remaining hops may still be dispatched under the chosen interruption
 * mode. The {{VAR:name}} grammar is pinned identical to the executor's
 * env_resolver._VAR_PATTERN by a same-source test (red line 6, no second parser).
 */
@Component
public class ApiRunSetPlanSupport {

    /** Single source: must equal executor/driver/env_resolver.py _VAR_PATTERN (test-enforced). */
    public static final String VAR_REF_PATTERN = "\\{\\{VAR:([A-Za-z0-9_][A-Za-z0-9_.\\-]*)\\}\\}";

    public static final String MODE_STOP = "STOP_ON_ASSERT_FAIL";
    public static final String MODE_CONTINUE = "CONTINUE_AND_SUMMARY";
    public static final String MODE_CHAIN_DEP = "CHAIN_DEP_ONLY";

    /** The three-form interruption domain (single source for validation everywhere). */
    public static final Set<String> MODES = Set.of(MODE_STOP, MODE_CONTINUE, MODE_CHAIN_DEP);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern PATTERN = Pattern.compile(VAR_REF_PATTERN);

    /** One pinned step: request id, tree position, references and extracted keys. */
    public record PinnedStep(long requestId, int seqNo, Set<String> references, Set<String> extracts) {
    }

    /** Terminal outcome of one child run (status: 1=pass 2=assert-fail 3=exec-error). */
    public record StepOutcome(long requestId, int status) {
        public boolean failed() {
            return status == 2 || status == 3;
        }
    }

    /** Reference set of one step plus its variable names. */
    public record StepRefs(Set<String> names) {
        public boolean hasAny() {
            return !names.isEmpty();
        }

        public boolean contains(String name) {
            return names.contains(name);
        }
    }

    /** The built plan, in pinned tree order. */
    public record RunSetPlan(List<PinnedStep> steps) {

        private static final StepRefs NONE = new StepRefs(Set.of());

        /** Variable names referenced by the step ({{VAR:...}} occurrences). */
        public StepRefs referencesOf(long requestId) {
            return steps.stream()
                    .filter(s -> s.requestId() == requestId)
                    .findFirst()
                    .map(s -> new StepRefs(s.references()))
                    .orElse(NONE);
        }

        /** Later steps that reference the pool key extracted by an earlier step. */
        public List<Long> consumersOf(String key) {
            List<Long> out = new ArrayList<>();
            for (PinnedStep s : steps) {
                if (s.references().contains(key) && !s.extracts().contains(key)) {
                    out.add(s.requestId());
                }
            }
            return out;
        }

        /**
         * Remaining steps still dispatchable given outcomes so far.
         * STOP: any failure freezes every remaining hop. CONTINUE: nothing is
         * suppressed. CHAIN_DEP: only hops whose referenced keys are produced
         * by a failed (or already suppressed) step drop out — transitively, so
         * a chain a→b→c prunes cleanly when a fails.
         */
        public List<Long> nextDispatchable(String mode, List<StepOutcome> outcomes) {
            if (!MODES.contains(mode)) {
                throw new ServiceException("中断模式仅支持 STOP_ON_ASSERT_FAIL / CONTINUE_AND_SUMMARY / "
                        + "CHAIN_DEP_ONLY，当前值「" + mode + "」", BizErrorCode.VALIDATION_ERROR);
            }
            Map<Long, StepOutcome> byRequest = new LinkedHashMap<>();
            outcomes.forEach(o -> byRequest.put(o.requestId(), o));
            List<Long> remaining = steps.stream()
                    .map(PinnedStep::requestId)
                    .filter(id -> !byRequest.containsKey(id))
                    .toList();
            if (mode.equals(MODE_CONTINUE)) {
                return remaining;
            }
            boolean anyFailure = outcomes.stream().anyMatch(StepOutcome::failed);
            if (mode.equals(MODE_STOP)) {
                return anyFailure ? List.of() : remaining;
            }
            // CHAIN_DEP: iterate to a fixed point — suppressed steps' extracts
            // must not feed further hops either.
            Set<String> brokenKeys = new HashSet<>();
            for (StepOutcome o : outcomes) {
                if (o.failed()) {
                    extractsOf(o.requestId()).forEach(brokenKeys::add);
                }
            }
            Set<Long> suppressed = new LinkedHashSet<>();
            boolean changed = true;
            while (changed) {
                changed = false;
                for (PinnedStep s : steps) {
                    if (suppressed.contains(s.requestId())) {
                        continue;
                    }
                    boolean dependsOnBroken = s.references().stream().anyMatch(brokenKeys::contains);
                    if (dependsOnBroken) {
                        suppressed.add(s.requestId());
                        extractsOf(s.requestId()).forEach(brokenKeys::add);
                        changed = true;
                    }
                }
            }
            return remaining.stream().filter(id -> !suppressed.contains(id)).toList();
        }

        private Set<String> extractsOf(long requestId) {
            return steps.stream()
                    .filter(s -> s.requestId() == requestId)
                    .findFirst()
                    .map(PinnedStep::extracts)
                    .orElse(Set.of());
        }
    }

    /**
     * Build the plan from a pinned definition snapshot. Tolerant on purpose:
     * unknown extra keys are ignored, a step without assertions still enters
     * the order (it may be referenced later even if it extracts nothing).
     */
    public RunSetPlan buildPlan(String pinnedJson) {
        JsonNode root;
        try {
            root = MAPPER.readTree(pinnedJson == null || pinnedJson.isBlank() ? "[]" : pinnedJson);
        } catch (Exception e) {
            throw new ServiceException("集合运行快照定义不是合法 JSON 数组", BizErrorCode.VALIDATION_ERROR);
        }
        if (!root.isArray()) {
            throw new ServiceException("集合运行快照定义必须是数组（每元素一个请求快照）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        List<PinnedStep> steps = new ArrayList<>();
        int seq = 0;
        for (JsonNode node : root) {
            seq++;
            long requestId = node.path("requestId").asLong();
            Set<String> extracts = new LinkedHashSet<>();
            JsonNode assertions = node.path("assertionsJson");
            if (assertions.isTextual()) {
                // jsonb read back as ::text — re-parse the row array
                try {
                    assertions = MAPPER.readTree(assertions.asText());
                } catch (Exception e) {
                    assertions = MAPPER.createArrayNode();
                }
            }
            for (JsonNode row : assertions) {
                if ("extract".equals(row.path("type").asText())) {
                    String saveAs = row.path("saveAs").asText("");
                    if (!saveAs.isBlank()) {
                        extracts.add(saveAs);
                    }
                }
            }
            Set<String> references = scanVarRefs(node.toString());
            steps.add(new PinnedStep(requestId, seq, references, extracts));
        }
        return new RunSetPlan(List.copyOf(steps));
    }

    /** Lift every {{VAR:name}} occurrence out of an arbitrary JSON subtree. */
    static Set<String> scanVarRefs(String rawJson) {
        Set<String> names = new LinkedHashSet<>();
        Matcher m = PATTERN.matcher(rawJson);
        while (m.find()) {
            names.add(m.group(1));
        }
        return names;
    }
}
