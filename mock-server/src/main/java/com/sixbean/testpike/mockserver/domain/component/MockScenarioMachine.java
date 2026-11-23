package com.sixbean.testpike.mockserver.domain.component;

import java.util.Set;

/**
 * One stateful scenario machine as the traffic plane needs it after load
 * : the declared state set (reduced through the shared
 * declaration kernel — this record never re-parses JSON on the serving path),
 * the lane scope, and the initial state a fresh lane starts from.
 *
 * @param name scenario label (unique within the space, key material)
 * @param scope lane spectrum: session | role | global (M-c ruling)
 * @param initialState state a lane with no stored value is treated as being in
 * @param declaredStates frozen declared-state set; advances outside it are refused
 */
public record MockScenarioMachine(
        String name,
        String scope,
        String initialState,
        Set<String> declaredStates) {

    public MockScenarioMachine {
        // LinkedHashSet, not Set.copyOf: declaration order is the editor's
        // state list and the trace display order (copyOf scrambles it).
        declaredStates = java.util.Collections.unmodifiableSet(
                new java.util.LinkedHashSet<>(declaredStates == null ? java.util.Set.of() : declaredStates));
    }
}
