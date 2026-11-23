package com.sixbean.testpike.core.batch.domain.component;

import com.sixbean.testpike.core.apiaccess.api.dto.ApiAccessGovernanceView;
import com.sixbean.testpike.core.apiaccess.domain.component.RowOwnershipDoor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
 * Batch-template family bridge on the read door: the governance row says "batchTemplate"/
 * "templateId" and this bridge resolves the owning project straight from the template table's
 * isolation column through the shared read (the template row carries its project since V24, so
 * no second ownership rule is opened). Properties held here: the entity name routes, a template
 * row resolves through the named-parameter query, a missing template resolves to nothing (fail
 * closed), a non-numeric argument never reaches the database, and the door refuses a non-member.
 */
class BatchTemplateRowProjectLookupTest {

    private static final long TEMPLATE_ID = 21L;
    private static final long OWNING_PROJECT = 7L;
    private static final long OPERATOR = 42L;

    private NamedParameterJdbcTemplate jdbcTemplate;
    private BatchTemplateRowProjectLookup lookup;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        lookup = new BatchTemplateRowProjectLookup(jdbcTemplate);
    }

    @Test
    void templateEntityNameRoutesTheDoor() {
        RowOwnershipDoor.stubRowRows(jdbcTemplate, List.of(OWNING_PROJECT));
        RowOwnershipDoor.assertEntityNameRoutes(lookup, "batchTemplate", anchoredTemplateRow(),
                "templateId", TEMPLATE_ID, OWNING_PROJECT, OPERATOR);
    }

    @Test
    void existingTemplateResolvesToItsOwningProject() {
        RowOwnershipDoor.stubRowRows(jdbcTemplate, List.of(OWNING_PROJECT));
        assertEquals(List.of(OWNING_PROJECT), lookup.ownerProjectIds(TEMPLATE_ID));
        assertEquals(List.of(OWNING_PROJECT), lookup.ownerProjectIds("88"),
                "path arguments arrive as strings and must resolve like numbers");
    }

    @Test
    void queryNamesTheTemplateTableNotTheBatchTable() {
        RowOwnershipDoor.stubRowRows(jdbcTemplate, List.of(OWNING_PROJECT));
        lookup.ownerProjectIds(TEMPLATE_ID);
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForList(sqlCaptor.capture(),
                eq(Map.of("id", TEMPLATE_ID)), eq(Long.class));
        assertTrue(sqlCaptor.getValue().contains("tp_batch_template"), sqlCaptor.getValue());
    }

    @Test
    void missingTemplateResolvesToNothing() {
        RowOwnershipDoor.stubRowRows(jdbcTemplate, List.of());
        RowOwnershipDoor.assertMissingRowIsRefused(lookup, anchoredTemplateRow(),
                "templateId", 999L, OPERATOR);
    }

    @Test
    @SuppressWarnings("unchecked")
    void nonNumericArgumentNeverReachesTheDatabase() {
        RowOwnershipDoor.assertNonNumericArgumentIsInert(lookup, "abc", null);
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(jdbcTemplate, never()).queryForList(anyString(), captor.capture(), eq(Long.class));
        assertEquals(0, captor.getAllValues().size(), "no query ran, so nothing was ever bound");
    }

    @Test
    void doorRefusesANonMemberThroughTheRealBridge() {
        RowOwnershipDoor.stubRowRows(jdbcTemplate, List.of(OWNING_PROJECT));
        String problem = RowOwnershipDoor.door(OPERATOR, false, lookup)
                .problem(anchoredTemplateRow(), OPERATOR, Map.of("templateId", TEMPLATE_ID));
        assertNotNull(problem);
        assertTrue(problem.contains("越权读取已被拒绝"), problem);
    }

    private ApiAccessGovernanceView anchoredTemplateRow() {
        return new ApiAccessGovernanceView("GET /internal/batches/templates/{templateId}",
                true, true, null, null, "batchTemplate", "templateId");
    }
}
