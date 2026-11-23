package com.sixbean.testpike.core.perf.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * B-3 full-link mix grouping: steps carrying the declared
 * {@code transaction} key collapse into pressure links in declaration order,
 * each with the weight its steps declared. This is the single grouping source —
 * the k6 template renders one executor block per link, the adapter derives the
 * dataset stride divisor from the same view, and the save/compile gates judge
 * the same declaration, so no face re-derives the mix (red line 6).
 * <p>Weights ride fractions of the declared total pressure: link weight over
 * the sum of distinct link weights. A flat scenario (no transaction key
 * anywhere) yields an empty list and every caller keeps the pre-B-3 shape.
 */
public record PerfMixGroups(List<Link> links) {

    /**
     * One transaction link: its name, the weight its steps declared (0 = the
     * group carries no weight anywhere = even split), and the member step
     * indices into the original step array (chain order preserved).
     */
    public record Link(String name, int weight, List<Integer> stepIndices) {
    }

    /** Sum of all distinct link weights (0 for a flat scenario). */
    public int totalWeight() {
        return links.stream().mapToInt(Link::weight).sum();
    }

    /** True when no step declared a transaction (flat chain — old shape). */
    public boolean isFlat() {
        return links.isEmpty();
    }

    /**
     * Arrival rate one link gets out of the scenario's total target rate:
     * declared weights split proportionally (weight / sum-of-weights); an
     * all-unweighted group set splits evenly. Rounding up keeps every link at
     * a positive rate — the total may ride slightly above the declared number
     * (never below: a silently dropped link would fake the mix shape).
     * Single source for the k6 template blocks and the adapter VU budget so
     * the emitted rates and the preAllocatedVUs divisor can never drift.
     */
    public int linkRate(Link link, int totalRate) {
        int groups = links.size();
        if (groups == 0 || totalRate <= 0) {
            return Math.max(totalRate, 0);
        }
        int total = totalWeight();
        if (total == 0) {
            return Math.max(1, (int) Math.ceil((double) totalRate / groups));
        }
        return Math.max(1, (int) Math.ceil((double) totalRate * link.weight() / total));
    }

    /**
     * k6 scenario-block key for one link: {@code link<i>_<sanitized name>} —
     * the index guarantees uniqueness even when two links share a spelling
     * after sanitization (k6 refuses duplicate scenario keys).
     */
    public String scenarioKey(Link link) {
        int at = links.indexOf(link);
        String sanitized = link.name().replaceAll("[^A-Za-z0-9_-]+", "_");
        if (sanitized.isBlank() || "_".equals(sanitized)) {
            sanitized = "tx";
        }
        return "link" + (at + 1) + "_" + sanitized;
    }

    /**
 * Pressure keys a link re-derives from the scenario total by weight
 * fraction (B-3): every arrival-rate driver plus the FIXED_VU pool size.
 * Declared-window/shape keys (durations, steps, loops, curve weights)
 * never scale — a link runs the same timeline shape at its own share of
 * the pressure. Shared by BOTH engines (k6 executor blocks, c-2β;
 * Gatling populations, c-2γ) so a per-link rate can never drift between
 * the two faces of the same declaration.
 */
    private static final List<String> LINK_SCALED_KEYS =
            List.of("targetRps", "peakRps", "baseRps", "startRps", "stepRps", "vu");

    /**
     * Weight-fraction view of the scenario pressure config for ONE link
     * (single scaling source: k6's adapter VU divisor and Gatling's per-link
     * injection both ride this, so emitted rates and pinned pools never
     * drift). Rounding up keeps every link positive — the summed pressure
     * may ride slightly above the declared total, never below (a silently
     * dropped share would fake the mix shape).
     */
    public ObjectNode scaledConfig(JsonNode config, Link link) {
        ObjectNode copy = config.deepCopy();
        for (String key : LINK_SCALED_KEYS) {
            JsonNode value = copy.get(key);
            if (value != null && value.isNumber()) {
                copy.put(key, linkRate(link, value.asInt()));
            }
        }
        return copy;
    }

    /**
     * k6 exec-function name for one link (same-file exec target): {@code tpLink<i>}
     * — index-based so any link name (including CJK or empty after sanitizing)
     * still yields a valid, collision-free JS identifier.
     */
    public String funcName(Link link) {
        return "tpLink" + (links.indexOf(link) + 1);
    }

    /**
     * Group the step array by its {@code transaction} text (blank/missing =
     * not part of a link; the save gate already guarantees the all-or-none
     * declaration shape before this runs). The same name seen twice merges
     * steps; the weight is taken from the first declaration (the save gate
     * rejects conflicting weights inside one group).
     */
    public static PerfMixGroups derive(JsonNode steps) {
        List<Link> links = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            String name = steps.get(i).path("transaction").asText("");
            if (name.isBlank()) {
                continue;
            }
            int weight = steps.get(i).path("weight").asInt(0);
            int at = -1;
            for (int j = 0; j < links.size(); j++) {
                if (links.get(j).name().equals(name)) {
                    at = j;
                    break;
                }
            }
            if (at < 0) {
                List<Integer> indices = new ArrayList<>();
                indices.add(i);
                links.add(new Link(name, weight, indices));
            } else {
                Link old = links.get(at);
                // A group that carries any weight rides the declared number;
                // an all-unweighted group stays 0 (even split).
                int merged = old.weight() != 0 ? old.weight() : weight;
                List<Integer> indices = new ArrayList<>(old.stepIndices());
                indices.add(i);
                links.set(at, new Link(old.name(), merged, indices));
            }
        }
        return new PerfMixGroups(links);
    }
}
