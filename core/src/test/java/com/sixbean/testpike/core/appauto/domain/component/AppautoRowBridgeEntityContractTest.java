package com.sixbean.testpike.core.appauto.domain.component;

import com.sixbean.testpike.core.apiaccess.domain.component.RowProjectLookup;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * Contract across every appauto row bridge: each governed entity in the family declares exactly one
 * governance {@code row_owner_entity} value and no two bridges share a name. The door routes by that
 * value into a map keyed on it — a duplicate name would silently drop one bridge and leave its rows
 * refused (or answered from the wrong table), so uniqueness is asserted here once instead of being
 * discovered at runtime. The device lease-run ledger deliberately has no bridge of its own: it is
 * anchored on the device row and reuses the device bridge.
 */
class AppautoRowBridgeEntityContractTest {

    @Test
    void everyAppautoBridgeDeclaresADistinctEntityName() {
        NamedParameterJdbcTemplate template = mock(NamedParameterJdbcTemplate.class);
        List<RowProjectLookup> bridges = List.of(
                new AppRowProjectLookup(template),
                new DeviceRowProjectLookup(template),
                new EnvProfileRowProjectLookup(template),
                new PopupRuleRowProjectLookup(template),
                new RecordDraftRowProjectLookup(template),
                new ReservationRowProjectLookup(template),
                new StepSuggestionRowProjectLookup(template),
                new TakeoverRowProjectLookup(template));
        Set<String> names = new HashSet<>();
        for (RowProjectLookup bridge : bridges) {
            names.add(bridge.entity());
        }
        assertEquals(bridges.size(), names.size(),
                "two appauto bridges share one entity name: " + names);
        assertEquals(Set.of("app", "device", "envProfile", "popupRule",
                "recordDraft", "reservation", "stepSuggestion", "takeover"), names);
    }
}
