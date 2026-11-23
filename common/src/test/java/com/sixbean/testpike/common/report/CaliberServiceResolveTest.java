package com.sixbean.testpike.common.report;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class CaliberServiceResolveTest {

    @Test
    void requireUnknownCodeFailsWithHumanMessage() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> CaliberService.require("not-exist-caliber"));
        assertEquals(BizErrorCode.NOT_FOUND, ex.getCode());
        assertTrue(ex.getMessage().contains("not-exist-caliber"), ex.getMessage());
        assertTrue(ex.getMessage().contains("口径"), ex.getMessage());
    }

    @Test
    void resolveFillsDeclaredParamsIntoQuery() {
        CaliberQuery q = CaliberService.resolve("pass-rate",
                CaliberService.scopeParams(7L, null, null));
        assertEquals("pass-rate", q.code());
        assertTrue(q.sql().contains(":projectId"), q.sql());
        assertEquals(7L, q.params().get("projectId"));
        assertTrue(q.params().containsKey("fromTime"));
        assertNull(q.params().get("fromTime"));
    }

    @Test
    void resolveRejectsMissingParamWithValidationCode() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> CaliberService.resolve("token-cost-month", Map.of()));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("month"), ex.getMessage());
    }

    @Test
    void resolveRejectsUndeclaredParamToPreventDrift() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> CaliberService.resolve("pass-rate", Map.of("projectId", 7L, "planId", 1L)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("planId"), ex.getMessage());
    }
}
