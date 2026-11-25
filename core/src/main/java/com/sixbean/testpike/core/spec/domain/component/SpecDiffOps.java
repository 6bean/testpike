package com.sixbean.testpike.core.spec.domain.component;

import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Single source of the contract-version endpoint diff: compares two parsed
 * snapshots by endpoint identity and splits the outcome into added / changed /
 * removed key sets (structure-hash material decides "changed").
 * <p>Extracted from the spec service implementation so the mock upgrade
 * planner consumes exactly the same judgement the upload badge and the change
 * analysis queue already use, instead of copying the rule across domains.
 */
public final class SpecDiffOps {

    private SpecDiffOps() {
    }

    public static SpecDiff diffOf(ParsedApiSpec prev, ParsedApiSpec current) {
        Map<String, String> prevHashes = new LinkedHashMap<>();
        for (ApiEndpoint ep : prev.endpoints()) {
            prevHashes.put(ep.endpointKey(), ep.structureHash());
        }
        Map<String, String> currentHashes = new LinkedHashMap<>();
        for (ApiEndpoint ep : current.endpoints()) {
            currentHashes.put(ep.endpointKey(), ep.structureHash());
        }
        Set<String> added = new TreeSet<>(currentHashes.keySet());
        added.removeAll(prevHashes.keySet());
        Set<String> removed = new TreeSet<>(prevHashes.keySet());
        removed.removeAll(currentHashes.keySet());
        Set<String> common = new HashSet<>(prevHashes.keySet());
        common.retainAll(currentHashes.keySet());
        Set<String> changed = new TreeSet<>();
        for (String key : common) {
            String a = prevHashes.get(key);
            String b = currentHashes.get(key);
            if (a == null || b == null || !a.equals(b)) {
                changed.add(key);
            }
        }
        return new SpecDiff(sortedCopy(added), sortedCopy(changed), sortedCopy(removed));
    }

    private static List<String> sortedCopy(Set<String> keys) {
        return keys.stream().sorted(Comparator.naturalOrder()).toList();
    }
}
