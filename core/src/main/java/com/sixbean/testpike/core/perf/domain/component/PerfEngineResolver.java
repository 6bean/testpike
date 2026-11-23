package com.sixbean.testpike.core.perf.domain.component;

import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Single resolution point for the engine a scenario actually presses with (W-d).
 * <p>An engine pinned on the scenario row wins; a blank/absent value falls back
 * to the K8 default-engine key (itself value-domain validated inside
 * {@link PerfConfigReader#defaultEngine()}). Every consumer — the dispatch
 * envelope, the launch-pinned pressure snapshot, and any future read face —
 * must route through this one door: deriving the effective engine twice from
 * different sources provably drifts (the dispatch pressed JMETER via the
 * default key while the report face re-read the blank scenario column and
 * silently dropped the approximation disclosure).
 * <p>This door never validates against {@link PerfRunDispatchSupport#ENGINE_VALUES}
 * itself — an unknown pinned value is the adapter routing table's honest
 * rejection to deliver (one rejection point, no second wording family).
 */
@Component
public class PerfEngineResolver {

    private final PerfConfigReader perfConfigReader;

    public PerfEngineResolver(PerfConfigReader perfConfigReader) {
        this.perfConfigReader = perfConfigReader;
    }

    /**
     * Resolve the effective engine from the raw scenario column value.
     *
     * @param pinnedEngine scenario engine value (null/blank = not pinned)
     * @return normalized engine token (upper-case, trimmed) — either the
     *         pinned value or the default-key truth, never null
     */
    public String resolve(Object pinnedEngine) {
        if (pinnedEngine == null || String.valueOf(pinnedEngine).isBlank()) {
            return perfConfigReader.defaultEngine();
        }
        return String.valueOf(pinnedEngine).trim().toUpperCase(Locale.ROOT);
    }
}
