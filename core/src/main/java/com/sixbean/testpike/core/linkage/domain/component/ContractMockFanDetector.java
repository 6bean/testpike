package com.sixbean.testpike.core.linkage.domain.component;

import com.sixbean.testpike.core.linkage.domain.service.LinkageEdgeDetector;
import com.sixbean.testpike.core.spec.domain.component.DerivedMockKeyLookup;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bean detector behind edge api_spec->mock_stub: a contract version edit
 * fans out to every contract-derived stub in the same project whose match
 * key equals one of the spec's folded endpoint keys.
 *
 * <p>The key->stub lookup is DerivedMockKeyLookup.stubIdsByKey, the same
 * single source the mock-stale badge and the change-analysis close path use
 * (a third copy of that SELECT would fork the truth). Folded keys come from
 * ContractSpecFoldedKeys, shared with the request-side detector.
 */
@Component
public class ContractMockFanDetector implements LinkageEdgeDetector {

    private final DerivedMockKeyLookup derivedMockKeyLookup;
    private final ContractSpecFoldedKeys specFoldedKeys;

    public ContractMockFanDetector(DerivedMockKeyLookup derivedMockKeyLookup,
                                   ContractSpecFoldedKeys specFoldedKeys) {
        this.derivedMockKeyLookup = derivedMockKeyLookup;
        this.specFoldedKeys = specFoldedKeys;
    }

    @Override
    public List<Long> detectDownstream(Long upstreamObjectId) {
        return specFoldedKeys.armedKeys(upstreamObjectId)
                .map(this::stubIdsMatching)
                .orElse(List.of());
    }

    /**
     * Contract-derived stubs in the spec's own project whose match key is one
     * of the spec's folded endpoint keys. The key->stub lookup stays in
     * DerivedMockKeyLookup (single source shared with the stale badge and the
     * change-analysis close path), so this side only intersects the fan.
     */
    private List<Long> stubIdsMatching(ContractSpecFoldedKeys.SpecFanKeys keys) {
        Map<String, List<Long>> stubIdsByKey =
                derivedMockKeyLookup.stubIdsByKey(keys.projectId());
        Set<Long> stubIds = new LinkedHashSet<>();
        for (String folded : keys.foldedKeys()) {
            List<Long> hits = stubIdsByKey.get(folded);
            if (hits != null) {
                stubIds.addAll(hits);
            }
        }
        return new ArrayList<>(stubIds);
    }
}
