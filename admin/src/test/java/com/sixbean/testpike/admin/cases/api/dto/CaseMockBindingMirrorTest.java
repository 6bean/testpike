package com.sixbean.testpike.admin.cases.api.dto;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * mirror contract: the admin case bridge must carry the declared
 * mock binding end-to-end. The core write/read records (CaseCreateInput /
 * CaseUpdateInput / CaseInfo) already gained a trailing mockSpaceId in ;
 * these DTOs are field-for-field mirrors deserialized straight from the core
 * JSON, so a missing or mis-named component silently drops the binding at the
 * bridge.
 * <p>Pinned invariants: mockSpaceId is the LAST record component, type Long
 * (null = untouched/unbound semantics ride on the object type), same name as
 * core — Jackson binds by name, so name drift = silent data loss, which is
 * exactly what this test makes impossible.
 */
class CaseMockBindingMirrorTest {

    private static void assertTrailingMockSpaceId(Class<? extends Record> dto) {
        RecordComponent[] components = dto.getRecordComponents();
        RecordComponent last = components[components.length - 1];
        assertEquals("mockSpaceId", last.getName(),
                dto.getSimpleName() + " must end with the mockSpaceId mirror component");
        assertEquals(Long.class, last.getType(),
                dto.getSimpleName() + ".mockSpaceId must be Long (null-able binding)");
    }

    @Test
    void createRequestMirrorsMockBinding() {
        assertTrailingMockSpaceId(CreateCaseRequest.class);
    }

    @Test
    void updateRequestMirrorsMockBinding() {
        assertTrailingMockSpaceId(UpdateCaseRequest.class);
    }

    @Test
    void caseViewMirrorsMockBinding() {
        assertTrailingMockSpaceId(CaseView.class);
    }
}
