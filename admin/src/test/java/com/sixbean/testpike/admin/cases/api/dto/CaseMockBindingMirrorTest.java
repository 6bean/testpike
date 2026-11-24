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

    private static void assertMockSpaceIdMirror(Class<? extends Record> dto) {
        // Jackson binds by name, so the mirror is pinned by name (position
        // carries no contract: fields may be appended after it, e.g. sourceFacet).
        for (RecordComponent component : dto.getRecordComponents()) {
            if ("mockSpaceId".equals(component.getName())) {
                assertEquals(Long.class, component.getType(),
                        dto.getSimpleName() + ".mockSpaceId must be Long (null-able binding)");
                return;
            }
        }
        throw new AssertionError(dto.getSimpleName() + " must carry the mockSpaceId mirror component");
    }

    @Test
    void createRequestMirrorsMockBinding() {
        assertMockSpaceIdMirror(CreateCaseRequest.class);
    }

    @Test
    void updateRequestMirrorsMockBinding() {
        assertMockSpaceIdMirror(UpdateCaseRequest.class);
    }

    @Test
    void caseViewMirrorsMockBinding() {
        assertMockSpaceIdMirror(CaseView.class);
    }
}
