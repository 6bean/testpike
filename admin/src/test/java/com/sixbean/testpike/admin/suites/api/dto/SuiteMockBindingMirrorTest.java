package com.sixbean.testpike.admin.suites.api.dto;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * mirror contract for the regression-suite bridge (same reasoning
 * as the case-side mirror test): suites carry the declarative mock binding
 * through SuiteCreateRequest/SuiteUpdateRequest into core SuiteCreateInput/
 * SuiteUpdateInput, and SuiteView mirrors core SuiteInfo for the read side.
 * mockSpaceId must be the trailing Long component with the exact core name —
 * name drift would silently drop the binding in Jackson round-tripping.
 */
class SuiteMockBindingMirrorTest {

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
        assertTrailingMockSpaceId(SuiteCreateRequest.class);
    }

    @Test
    void updateRequestMirrorsMockBinding() {
        assertTrailingMockSpaceId(SuiteUpdateRequest.class);
    }

    @Test
    void suiteViewMirrorsMockBinding() {
        assertTrailingMockSpaceId(SuiteView.class);
    }
}
