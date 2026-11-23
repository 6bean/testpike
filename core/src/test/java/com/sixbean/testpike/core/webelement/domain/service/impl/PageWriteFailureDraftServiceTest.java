package com.sixbean.testpike.core.webelement.domain.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.webelement.PageWriteProfileView;
import com.sixbean.testpike.core.aichat.domain.service.SystemDraftService;
import com.sixbean.testpike.core.webelement.domain.service.PageWriteProfileQueryService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * One-click draft conversion over booked network failures :
 * the converter reads ONE row through the shared query door (strict project
 * segment, no leak), picks the failure triple by index and files a pending
 * CASE_WRITE draft whose payload speaks the exact shape the landing strategy
 * consumes (title / precondition / expectedResult / steps[description,
 * stepNo, isAssert]) — never a self-apply path; the generic confirmation
 * door still decides whether the case is ever written.
 */
class PageWriteFailureDraftServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final PageWriteProfileQueryService profiles = mock(PageWriteProfileQueryService.class);
    private final SystemDraftService drafts = mock(SystemDraftService.class);
    private final PageWriteFailureDraftServiceImpl service =
            new PageWriteFailureDraftServiceImpl(profiles, drafts, mapper);

    private PageWriteProfileView rowWith(String... failureJsons) throws Exception {
        List<JsonNode> failures = new ArrayList<>();
        for (String json : failureJsons) {
            failures.add(mapper.readTree(json));
        }
        return new PageWriteProfileView(5L, 42L, "k", "http://app.test/notes",
                List.of("POST"), List.of(), failures, true, 8L,
                LocalDateTime.now(), LocalDateTime.now());
    }

    @Test
    void failureRowBecomesCaseDraftWithPinnedPayloadShape() throws Exception {
        when(profiles.findByIdInProject(42L, 5L)).thenReturn(
                rowWith("{\"url\":\"http://app.test/api/save\",\"method\":\"POST\",\"status\":500,\"errorType\":1,\"failureText\":null}"));
        when(drafts.postStructuredDraft(anyLong(), anyString(), anyString(), anyString(), any())).thenReturn(111L);

        long draftId = service.toCaseDraft(42L, 5L, 0);

        assertEquals(111L, draftId);
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(drafts).postStructuredDraft(eq(42L), eq("CASE_WRITE"), summary.capture(),
                payload.capture(), isNull());
        JsonNode body = mapper.readTree(payload.getValue());
        assertTrue(body.hasNonNull("title"));
        assertTrue(body.hasNonNull("precondition"));
        assertTrue(body.hasNonNull("expectedResult"));
        assertTrue(body.path("steps").isArray() && body.path("steps").size() >= 1);
        JsonNode step = body.path("steps").get(0);
        assertTrue(step.hasNonNull("description"));
        assertEquals(1, step.path("stepNo").asInt());
        assertTrue(step.has("isAssert"));
        assertTrue(body.path("precondition").asText().contains("/api/save"));
        assertTrue(summary.getValue().contains("POST"));
    }

    @Test
    void outOfRangeIndexRefusesNamingBoundsAndRegistersNothing() throws Exception {
        when(profiles.findByIdInProject(42L, 5L)).thenReturn(
                rowWith("{\"url\":\"u\",\"method\":\"GET\",\"status\":404,\"errorType\":1}"));

        ServiceException error = assertThrows(ServiceException.class, () -> service.toCaseDraft(42L, 5L, 3));
        assertTrue(error.getMessage().contains("3"));
        assertTrue(error.getMessage().contains("1"));
        verifyNoInteractions(drafts);
    }

    @Test
    void foreignOrMissingRowRefusesWithoutLeak() {
        when(profiles.findByIdInProject(42L, 5L)).thenReturn(null);

        ServiceException error = assertThrows(ServiceException.class, () -> service.toCaseDraft(42L, 5L, 0));
        assertTrue(error.getMessage().contains("5"));
        verifyNoInteractions(drafts);
    }

    @Test
    void missingProjectAnchorRefusesBeforeAnyRead() {
        ServiceException error = assertThrows(ServiceException.class, () -> service.toCaseDraft(null, 5L, 0));
        assertTrue(error.getMessage().contains("projectId"));
        verify(profiles, never()).findByIdInProject(anyLong(), anyLong());
        verifyNoInteractions(drafts);
    }

    @Test
    void environmentFailurePhrasesTheExpectationAsConnectivity() throws Exception {
        when(profiles.findByIdInProject(42L, 5L)).thenReturn(
                rowWith("{\"url\":\"http://app.test/api/x\",\"method\":\"GET\",\"status\":null,\"errorType\":3,\"failureText\":\"connection reset by peer\"}"));
        when(drafts.postStructuredDraft(any(), any(), any(), any(), any())).thenReturn(9L);

        service.toCaseDraft(42L, 5L, 0);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(drafts).postStructuredDraft(eq(42L), eq("CASE_WRITE"), anyString(),
                payload.capture(), isNull());
        JsonNode body = mapper.readTree(payload.getValue());
        assertTrue(body.path("expectedResult").asText().contains("连通"));
    }
}
