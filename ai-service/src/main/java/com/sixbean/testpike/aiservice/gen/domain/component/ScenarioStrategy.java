package com.sixbean.testpike.aiservice.gen.domain.component;

import com.sixbean.testpike.aiservice.gen.api.dto.ScenarioItem;
import com.sixbean.testpike.aiservice.gen.api.dto.ScenarioKind;

import java.util.ArrayList;
import java.util.List;

/**
 * Priority strategy of the stage0 scenario enumeration (, ruling
 * section 3 item 3): P0 requirements are the ones the product depends on, so
 * their list must keep the counter examples and boundaries the proposer is
 * asked to expand; P2 requirements only need the main flow (with equivalent
 * positives), trimming the scenario noise that would multiply their case
 * count; P1 keeps everything the proposer produced. Mandatory-kind gaps are
 * reported as disputes for the shared deliberation ring's checker segment —
 * this class only states which kinds are mandatory, it never runs a second
 * loop .
 * <p>The per-requirement cap and the master switch are hot K8 rows
 * ({@code ai.gen.scenario-expand.enabled} / {@code
 * ai.gen.scenario-max-per-requirement}); an unreadable row degrades to the
 * factory default instead of taking the generation run down. Switch OFF
 * restores the legacy one-line-one-case behaviour entirely — the caller
 * checks it before ever reaching these methods.
 */
public final class ScenarioStrategy {

    /** K8 master switch (shipped on; off = legacy one-case-per-line). */
    public static final String KEY_EXPAND_ENABLED = "ai.gen.scenario-expand.enabled";

    /** K8 per-requirement scenario cap, factory 8, adjudicated range 1..20. */
    public static final String KEY_MAX_PER_REQUIREMENT = "ai.gen.scenario-max-per-requirement";

    public static final int FACTORY_MAX_SCENARIOS = 8;

    private static final int MIN_SCENARIOS = 1;
    private static final int MAX_SCENARIOS = 20;

    /** priority ints follow the GeneratedCase convention: 0=P0 1=P1 2=P2. */
    private static final int PRIORITY_P0 = 0;
    private static final int PRIORITY_P2 = 2;

    private ScenarioStrategy() {
    }

    /**
     * Scenarios that survive the priority filter, original order kept: P2
     * drops counter examples, boundaries and state combinations, everything
     * else keeps the whole list.
     */
    public static List<ScenarioItem> retainedForPriority(List<ScenarioItem> items, int priority) {
        if (priority != PRIORITY_P2) {
            return List.copyOf(items);
        }
        List<ScenarioItem> kept = new ArrayList<>();
        for (ScenarioItem item : items) {
            if (item.kind() == ScenarioKind.MAIN_FLOW || item.kind() == ScenarioKind.EQUIVALENCE) {
                kept.add(item);
            }
        }
        return kept;
    }

    /**
     * Mandatory kinds still absent after the filter — the checker segment of
     * the deliberation ring feeds these back as named disputes (empty list =
     * converged). P0 mandates counter example + boundary, P2 mandates the
     * main flow, P1 mandates nothing beyond the list itself.
     */
    public static List<ScenarioKind> missingMandatoryKinds(int priority, List<ScenarioItem> items) {
        if (priority == PRIORITY_P0) {
            List<ScenarioKind> missing = new ArrayList<>();
            if (items.stream().noneMatch(i -> i.kind() == ScenarioKind.COUNTER_EXAMPLE)) {
                missing.add(ScenarioKind.COUNTER_EXAMPLE);
            }
            if (items.stream().noneMatch(i -> i.kind() == ScenarioKind.BOUNDARY)) {
                missing.add(ScenarioKind.BOUNDARY);
            }
            return missing;
        }
        if (priority == PRIORITY_P2 && items.stream().noneMatch(i -> i.kind() == ScenarioKind.MAIN_FLOW)) {
            return List.of(ScenarioKind.MAIN_FLOW);
        }
        return List.of();
    }

    /**
     * Cap from the raw config value: blank/unreadable falls back to 8,
     * anything else clamps into 1..20 whatever the row says.
     */
    public static int scenarioCap(String rawMaxConfigValue) {
        if (rawMaxConfigValue == null || rawMaxConfigValue.isBlank()) {
            return FACTORY_MAX_SCENARIOS;
        }
        try {
            int parsed = Integer.parseInt(rawMaxConfigValue.trim());
            return Math.min(Math.max(parsed, MIN_SCENARIOS), MAX_SCENARIOS);
        } catch (NumberFormatException unreadable) {
            return FACTORY_MAX_SCENARIOS;
        }
    }

    /** Trims the list to the cap (selection preview never shows more). */
    public static List<ScenarioItem> cap(List<ScenarioItem> items, int maxScenarios) {
        return items.size() <= maxScenarios ? items : List.copyOf(items.subList(0, maxScenarios));
    }

    /**
     * One-line prompt brief for the proposer segment: which kinds the draft
     * must expand for this priority (P0 rich, P2 main flow only, P1 standard).
     * Text only — no model call, no second loop.
     */
    public static String proposerKindBrief(int priority) {
        if (priority == PRIORITY_P2) {
            return "只列主流程与等价正例（main-flow、equivalence），不要反例、边界与状态组合";
        }
        if (priority == PRIORITY_P0) {
            return "必须覆盖 main-flow、equivalence，并额外扩出 counter-example 与 boundary，"
                    + "条件允许时补 state-combination";
        }
        return "覆盖 main-flow 与 equivalence，并按需求点特征补充 counter-example、boundary、state-combination";
    }
}
