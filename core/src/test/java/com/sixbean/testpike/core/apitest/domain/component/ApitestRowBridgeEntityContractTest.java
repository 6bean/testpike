package com.sixbean.testpike.core.apitest.domain.component;

import com.sixbean.testpike.core.apiaccess.domain.component.RowProjectLookup;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * Contract across every apitest row bridge: each governed entity in the family declares exactly
 * one governance {@code row_owner_entity} value and no two bridges share a name. The door routes
 * by that value into a map keyed on it — a duplicate name would silently drop one bridge and
 * leave its rows refused (or worse, answered by the wrong table), so uniqueness is asserted here
 * once instead of discovered at runtime.
 */
class ApitestRowBridgeEntityContractTest {

    @Test
    void everyApitestBridgeDeclaresADistinctEntityName() {
        NamedParameterJdbcTemplate template = mock(NamedParameterJdbcTemplate.class);
        List<RowProjectLookup> bridges = List.of(
                new CollectionRowProjectLookup(template),
                new RequestRowProjectLookup(template),
                new FlowTemplateRowProjectLookup(template),
                new RunSetRowProjectLookup(template),
                new RunRowProjectLookup(template),
                new StepAliasRowProjectLookup(template));
        Set<String> names = new HashSet<>();
        for (RowProjectLookup bridge : bridges) {
            names.add(bridge.entity());
        }
        assertEquals(bridges.size(), names.size(),
                "two apitest bridges share one entity name: " + names);
        assertEquals(Set.of("collection", "request", "flowTemplate",
                "runSet", "run", "stepAlias"), names);
    }
}
