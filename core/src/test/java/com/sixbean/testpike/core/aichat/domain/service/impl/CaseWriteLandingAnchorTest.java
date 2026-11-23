package com.sixbean.testpike.core.aichat.domain.service.impl;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.aichat.api.dto.ChatSessionInfo;
import com.sixbean.testpike.core.aichat.domain.component.DraftProjectAnchor;
import com.sixbean.testpike.core.aichat.domain.component.RevisionMergeGate;
import com.sixbean.testpike.core.aichat.domain.service.ChatSessionService;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Landing attribution for case drafts that have no chat session (system
 * origin, filed by the page write-risk converter and other mint doors): the
 * write must aim at the row's own project anchor column instead of
 * dereferencing a session that does not exist — before this spectrum the
 * confirm click died on a raw NullPointerException. Cards that DO carry a
 * session keep their original byte-identical path: the session decides the
 * project segment, never the payload (H1 red line unchanged).
 */
class CaseWriteLandingAnchorTest {

    private final CaseService caseService = mock(CaseService.class);
    private final ChatSessionService sessionService = mock(ChatSessionService.class);
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final RevisionMergeGate mergeGate = mock(RevisionMergeGate.class);
    private final CaseWriteDraftStrategy strategy = new CaseWriteDraftStrategy(
            caseService, sessionService, jdbcTemplate, mergeGate, new DraftProjectAnchor(sessionService));

    private static Map<String, Object> row(Long sessionId, Long projectId, String payloadJson) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", 1L);
        row.put("session_id", sessionId);
        row.put("project_id", projectId);
        row.put("payload_json", payloadJson);
        return row;
    }

    private static CaseInfo createdCase(long id, int version) {
        return new CaseInfo(id, "C-" + id, 0L, "t", null, null, 2, 80, "web", 1, null, version,
                null, null, 1, null, 1L, null, null, List.of(), 0, null, null, 0,
                null, null, null, null, null, null);
    }

    @Test
    void sessionlessCardLandsIntoItsStoredProjectAnchor() {
        when(caseService.create(anyLong(), any())).thenReturn(createdCase(77L, 1));

        strategy.land(row(null, 42L, "{\"title\":\"verify failure handling\"}"), 9L);

        ArgumentCaptor<CaseCreateInput> input = ArgumentCaptor.forClass(CaseCreateInput.class);
        verify(caseService).create(eq(9L), input.capture());
        assertEquals(42L, input.getValue().projectId());
        verify(sessionService, never()).findById(anyLong());
        verify(jdbcTemplate).update(contains("UPDATE tp_ai_chat_draft SET target_id"), eq(77L), eq(1L));
    }

    @Test
    void sessionlessCardWithoutAnchorRefusesInActionableWords() {
        ServiceException error = assertThrows(ServiceException.class, () -> strategy.land(
                row(null, null, "{\"title\":\"t\"}"), 9L));
        assertTrue(error.getMessage().contains("缺少所属项目"),
                "refusal must name the missing anchor: " + error.getMessage());
        verifyNoInteractions(caseService);
    }

    @Test
    void dialogCardStillLandsIntoTheSessionProject() {
        when(sessionService.findById(7L))
                .thenReturn(new ChatSessionInfo(7L, 3L, 9L, "chat", 1, null, 1, 0L, 0L));
        when(caseService.create(anyLong(), any())).thenReturn(createdCase(88L, 1));

        strategy.land(row(7L, null, "{\"title\":\"t\"}"), 9L);

        ArgumentCaptor<CaseCreateInput> input = ArgumentCaptor.forClass(CaseCreateInput.class);
        verify(caseService).create(eq(9L), input.capture());
        assertEquals(3L, input.getValue().projectId());
    }
}
