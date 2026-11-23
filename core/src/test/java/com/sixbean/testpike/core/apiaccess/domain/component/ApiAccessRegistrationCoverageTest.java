package com.sixbean.testpike.core.apiaccess.domain.component;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.apiaccess.api.dto.ApiEndpointSpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The governance ledger is exhaustive: every entry the runtime catalog knows carries an explicit
 * row (opened faces documented, closed faces refused with a reader-facing reason). The 2026-11
 * full-registration batch made this the normal state; the wall "not reviewed yet" may now only
 * ever appear for a genuinely NEW endpoint between its code landing and its registration - which
 * is exactly what this contract catches, naming the missing keys so the adding developer closes
 * the gap in the same batch (registration system: adding an endpoint means stating a verdict).
 *
 * <p>Bidirectional self-proof (a coverage check that cannot fail is not a coverage check): the
 * same diff helper is asserted against a synthetic catalog with one extra key - that key must be
 * reported - and against the live catalog against the live ledger - zero gaps.
 */
@SpringBootTest(classes = CoreApplication.class)
class ApiAccessRegistrationCoverageTest {

    @Autowired
    private ApiEndpointCatalog catalog;

    @Autowired
    private ApiAccessGovernanceRepository governance;

    /** Keys in the catalog without a governance row, sorted for a readable failure. */
    private static Set<String> gaps(Map<String, ApiEndpointSpec> catalogEntries, Set<String> registered) {
        return catalogEntries.keySet().stream()
                .filter(key -> !registered.contains(key))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    @Test
    void everyCatalogEntryHasAGovernanceRow() {
        Set<String> registered = governance.loadAll().keySet();
        Set<String> missing = gaps(catalog.endpoints(), registered);
        assertTrue(missing.isEmpty(),
                "以下接口条目还没有登记行（新接口落码须同批登记结论，"
                        + "用仓内登记覆盖闸的 --audit/--emit-sql 姿势补齐）：" + missing);
        assertFalse(registered.isEmpty(), "治理账整体为空时不得假绿通过");
    }

    @Test
    void theCoverageHelperActuallyDetectsAGap() {
        Map<String, ApiEndpointSpec> live = catalog.endpoints();
        ApiEndpointSpec sample = live.values().iterator().next();
        String syntheticKey = sample.key() + " ##coverage-selftest##";
        Set<String> registered = governance.loadAll().keySet();
        Set<String> missing = gaps(Map.of(syntheticKey, sample), registered);
        assertEquals(Set.of(syntheticKey), missing, "检出逻辑失效即本契约形同虚设");
    }
}
