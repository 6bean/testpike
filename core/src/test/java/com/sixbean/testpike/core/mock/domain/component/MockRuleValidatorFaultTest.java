package com.sixbean.testpike.core.mock.domain.component;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.mock.MockMatchEngine;
import com.sixbean.testpike.core.mock.api.dto.MockStubInput;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Save-chain fault parameter-domain gate: a half-configured stub fault must be
 * refused with plain words before the row lands, sharing the spectrum with the
 * data-plane injector (a legal fault must pass untouched).
 */
class MockRuleValidatorFaultTest {

    private final MockRuleValidator validator = new MockRuleValidator(new MockMatchEngine());

    private static MockStubInput stubWithResponse(String responseJson) {
        return new MockStubInput("fault-gate", 10, "GET", "exact", "/demo",
                null, responseJson, null, null, null, "manual", null, true);
    }

    @Test
    void legalStubFaultPassesTheSaveGate() {
        validator.validateStub(stubWithResponse(
                "{\"status\":200,\"fault\":{\"type\":\"delay\",\"minMs\":100,\"maxMs\":800,"
                        + "\"probability\":30}}"));
    }

    @Test
    void absentFaultKeyPassesTheSaveGate() {
        validator.validateStub(stubWithResponse("{\"status\":200}"));
    }

    @Test
    void unknownFaultTypeIsRefusedAtSaveTime() {
        // the mistake must surface in the editor, not as a 500 on the traffic port
        ServiceException ex = assertThrows(ServiceException.class, () ->
                validator.validateStub(stubWithResponse(
                        "{\"fault\":{\"type\":\"explode\"}}")));
        assertTrue(ex.getMessage().contains("explode"), ex.getMessage());
    }

    @Test
    void nonFiveXxStatusFaultIsRefusedAtSaveTime() {
        ServiceException ex = assertThrows(ServiceException.class, () ->
                validator.validateStub(stubWithResponse(
                        "{\"fault\":{\"type\":\"status\",\"statusCode\":404}}")));
        assertTrue(ex.getMessage().contains("404"), ex.getMessage());
    }

    @Test
    void invertedDelayIntervalIsRefusedAtSaveTime() {
        assertThrows(ServiceException.class, () ->
                validator.validateStub(stubWithResponse(
                        "{\"fault\":{\"type\":\"delay\",\"minMs\":5000,\"maxMs\":100}}")));
    }

    @Test
    void malformedFaultOnPlainTextBodyIsRefusedAtSaveTime() {
        ServiceException ex = assertThrows(ServiceException.class, () ->
                validator.validateStub(stubWithResponse(
                        "{\"contentType\":\"text/plain\",\"fault\":{\"type\":\"malformed\"}}")));
        assertTrue(ex.getMessage().contains("malformed") || ex.getMessage().contains("JSON"),
                ex.getMessage());
    }

    @Test
    void probabilityOutOfRangeIsRefusedAtSaveTime() {
        assertThrows(ServiceException.class, () ->
                validator.validateStub(stubWithResponse(
                        "{\"fault\":{\"type\":\"disconnect\",\"probability\":120}}")));
    }

    @Test
    void faultKeyOfWrongKindIsRefusedAtSaveTime() {
        assertThrows(ServiceException.class, () ->
                validator.validateStub(stubWithResponse("{\"fault\":\"delay\"}")));
    }
}
