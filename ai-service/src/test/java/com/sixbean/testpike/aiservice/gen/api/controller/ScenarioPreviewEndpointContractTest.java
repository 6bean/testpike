package com.sixbean.testpike.aiservice.gen.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.api.dto.ScenarioEnumerationResult;
import com.sixbean.testpike.aiservice.gen.api.dto.ScenarioItem;
import com.sixbean.testpike.aiservice.gen.api.dto.ScenarioKind;
import com.sixbean.testpike.aiservice.gen.api.dto.ScenarioPreviewRequest;
import com.sixbean.testpike.aiservice.gen.domain.service.ScenarioEnumerationService;
import com.sixbean.testpike.common.clientobject.Response;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Stage0 scenario-preview internal door contract test :
 * (1) the controller forwards the body fields to the enumeration service in the
 * documented argument order and returns the outcome untouched — the core
 * preview ledger reads the same field names it deserializes into
 * ScenarioPreviewResult, so no field may be renamed or dropped here;
 * (2) scenario kinds travel on the wire as their hyphen codes (main-flow,
 * counter-example, …). Without the JSON binding the enum would serialize as
 * the constant name (MAIN_FLOW), forking a second vocabulary between the
 * enumeration parser and the persisted preview rows.
 * <p>The enumeration service is the isolated seam at L2; the live
 * core→ai-service hop rides the L3 window.
 */
class ScenarioPreviewEndpointContractTest {

    private final ScenarioEnumerationService enumerationService = mock(ScenarioEnumerationService.class);
    private final ScenarioPreviewInternalController controller =
            new ScenarioPreviewInternalController(enumerationService);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void enumerationOutcomeIsForwardedUntouchedUnderContractFieldNames() {
        ScenarioEnumerationResult outcome = new ScenarioEnumerationResult(true,
                List.of(new ScenarioItem(ScenarioKind.MAIN_FLOW, "正常登录", "用户输入账号密码登录", null)),
                List.of(), 2, 3, true);
        when(enumerationService.enumerate(eq("用户输入账号密码登录"), eq(0), eq(7L), eq(5L), eq(9L)))
                .thenReturn(outcome);

        Response<ScenarioEnumerationResult> response = controller.preview(
                new ScenarioPreviewRequest(7L, "用户输入账号密码登录", 0, 5L, 9L));

        verify(enumerationService).enumerate("用户输入账号密码登录", 0, 7L, 5L, 9L);
        assertEquals(outcome, response.getData(), "preview body must pass through without reshaping");
    }

    @Test
    void scenarioKindSerializesAsHyphenCodeAndParsesBack() throws Exception {
        String json = mapper.writeValueAsString(ScenarioKind.STATE_COMBINATION);
        assertEquals("\"state-combination\"", json,
                "wire form must be the hyphen code shared with the enumeration parser");
        assertEquals(ScenarioKind.COUNTER_EXAMPLE,
                mapper.readValue("\"counter-example\"", ScenarioKind.class));
    }
}
