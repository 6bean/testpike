package com.sixbean.testpike.core.batch.domain.component;

import com.sixbean.testpike.core.apiaccess.api.dto.ApiAccessGovernanceView;
import com.sixbean.testpike.core.apiaccess.domain.component.RowOwnershipDoor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Batch family bridge on the read door: the governance row says "batch"/"id" and this bridge
 * resolves the owning project straight from the batch table's isolation column. Properties held
 * here: the entity name routes, a batch row resolves through the named-parameter query, a missing
 * batch resolves to nothing (fail closed), and a non-numeric argument never reaches the database.
 */
class BatchRowProjectLookupTest {

    private static final long BATCH_ID = 7L;
    private static final long OWNING_PROJECT = 7L;
    private static final long OPERATOR = 42L;

    private NamedParameterJdbcTemplate jdbcTemplate;
    private BatchRowProjectLookup lookup;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        lookup = new BatchRowProjectLookup(jdbcTemplate);
    }

    @Test
    void batchEntityNameRoutesTheDoor() {
        RowOwnershipDoor.stubRowRows(jdbcTemplate, List.of(OWNING_PROJECT));
        RowOwnershipDoor.assertEntityNameRoutes(lookup, "batch", anchoredBatchRow(),
                "id", BATCH_ID, OWNING_PROJECT, OPERATOR);
    }

    @Test
    void existingBatchResolvesToItsOwningProject() {
        RowOwnershipDoor.stubRowRows(jdbcTemplate, List.of(OWNING_PROJECT));
        assertEquals(List.of(OWNING_PROJECT), lookup.ownerProjectIds(BATCH_ID));
        assertEquals(List.of(OWNING_PROJECT), lookup.ownerProjectIds("88"),
                "path arguments arrive as strings and must resolve like numbers");
    }

    @Test
    void missingBatchResolvesToNothing() {
        RowOwnershipDoor.stubRowRows(jdbcTemplate, List.of());
        RowOwnershipDoor.assertMissingRowIsRefused(lookup, anchoredBatchRow(), "id", 999L, OPERATOR);
    }

    @Test
    @SuppressWarnings("unchecked")
    void nonNumericArgumentNeverReachesTheDatabase() {
        RowOwnershipDoor.assertNonNumericArgumentIsInert(lookup, "abc", null);
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(jdbcTemplate, never()).queryForList(anyString(), captor.capture(), eq(Long.class));
        assertEquals(0, captor.getAllValues().size(), "no query ran, so nothing was ever bound");
    }

    private ApiAccessGovernanceView anchoredBatchRow() {
        return new ApiAccessGovernanceView("GET /internal/batches/{id}", true, true,
                null, null, "batch", "id");
    }

    @Test
    void doorRefusesANonMemberThroughTheRealBridge() {
        RowOwnershipDoor.stubRowRows(jdbcTemplate, List.of(OWNING_PROJECT));
        String problem = RowOwnershipDoor.door(OPERATOR, false, lookup)
                .problem(anchoredBatchRow(), OPERATOR, Map.of("id", BATCH_ID));
        assertNotNull(problem);
        assertTrue(problem.contains("越权读取已被拒绝"), problem);
    }
}
