package com.sixbean.testpike.core.aichat.domain.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.aichat.api.dto.ChatSessionInfo;
import com.sixbean.testpike.core.aichat.domain.component.DraftConfirmTokenStore;
import com.sixbean.testpike.core.aichat.domain.component.DraftQueueRenewalPolicy;
import com.sixbean.testpike.core.aichat.domain.service.ChatSessionService;
import com.sixbean.testpike.core.audit.domain.service.AuditLogService;
import com.sixbean.testpike.core.behavior.domain.component.BehaviorOutcomeIntake;
import com.sixbean.testpike.core.project.domain.service.ProjectAccessService;
import com.sixbean.testpike.core.rbac.domain.service.RoleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Checked-subset door for login-state draft cards ( piece D3, adjudicated
 * option B): before the confirm ticket is issued, the human may re-tick which
 * candidate rows the card is allowed to authorise. The door runs the SAME
 * guard order as every confirmation door (draft exists -> session ownership
 * -> still pending), then fails closed on any submitted name the server-side
 * snapshot does not carry as a candidate. Only the selected flags move: the
 * routing credential stays, values never enter the payload (piece E red line),
 * and a replay of the already-current set writes nothing (R28 idempotence).
 * The shared ApplyDraftRequest and the apply guard sequence are not touched.
 */
class LoginStateCheckedSubsetTest {

    private static final String SNAPSHOT =
            "{\"stagingToken\":\"tok-1\",\"stateName\":\"s\","
                    + "\"entries\":[{\"name\":\"SESSION\",\"selected\":true},"
                    + "{\"name\":\"theme\",\"selected\":false}]}";

    private JdbcTemplate jdbcTemplate;
    private NamedParameterJdbcTemplate named;
    private ChatSessionService sessionService;
    private DraftConfirmationServiceImpl service;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        named = mock(NamedParameterJdbcTemplate.class);
        sessionService = mock(ChatSessionService.class);
        service = new DraftConfirmationServiceImpl(jdbcTemplate, named, sessionService,
                mock(ProjectAccessService.class), mock(RoleService.class),
                mock(DraftConfirmTokenStore.class), mock(AuditLogService.class),
                mock(BehaviorOutcomeIntake.class), mock(DraftQueueRenewalPolicy.class),
                mock(com.sixbean.testpike.core.linkage.domain.service
                        .LinkageImpactConsumptionService.class),
                new com.sixbean.testpike.core.aichat.domain.component
                        .DraftProjectAnchor(sessionService),
                List.of());
        when(sessionService.requireOwned(anyLong(), anyLong()))
                .thenReturn(new ChatSessionInfo(5L, 77L, 9L, "t", 0, null, 1, 0L, 0L));
    }

    private void givenDraft(String payloadJson, int status) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", 900L);
        row.put("session_id", 5L);
        row.put("message_id", 12L);
        row.put("project_id", null);
        row.put("object_type", "LOGIN_STATE_WRITE");
        row.put("target_id", 0L);
        row.put("base_version", 0);
        row.put("schema_version", 1);
        row.put("payload_json", payloadJson);
        row.put("status", status);
        when(jdbcTemplate.queryForMap(anyString(), any())).thenReturn(row);
    }

    @Test
    void appliedDraftRefusesTheSubsetEdit() {
        givenDraft(SNAPSHOT, 2);
        ServiceException error = assertThrows(ServiceException.class,
                () -> service.updateCheckedSubset(9L, 900L, List.of("theme")));
        assertTrue(error.getMessage().contains("已应用"), error.getMessage());
        verify(named, never()).update(anyString(), any(MapSqlParameterSource.class));
    }

    @Test
    void foreignSessionRefusesBeforeAnythingMoves() {
        givenDraft(SNAPSHOT, 1);
        when(sessionService.requireOwned(anyLong(), anyLong()))
                .thenThrow(new ServiceException("forbidden", BizErrorCode.FORBIDDEN));
        assertThrows(ServiceException.class,
                () -> service.updateCheckedSubset(9L, 900L, List.of("theme")));
        verify(named, never()).update(anyString(), any(MapSqlParameterSource.class));
    }

    @Test
    void nameOutsideTheSnapshotCandidatesIsRefusedClosed() {
        givenDraft(SNAPSHOT, 1);
        ServiceException error = assertThrows(ServiceException.class,
                () -> service.updateCheckedSubset(9L, 900L, List.of("GHOST")));
        assertTrue(error.getMessage().contains("GHOST"),
                "the refusal must name the row that is not a candidate");
        verify(named, never()).update(anyString(), any(MapSqlParameterSource.class));
    }

    @Test
    void writeBackFlipsOnlySelectedFlagsAndKeepsEveryCandidate() throws Exception {
        givenDraft(SNAPSHOT, 1);
        when(named.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1);
        int checked = service.updateCheckedSubset(9L, 900L, List.of("theme"));
        assertEquals(1, checked);

        ArgumentCaptor<MapSqlParameterSource> captor =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(named).update(anyString(), captor.capture());
        String payload = (String) captor.getValue().getValue("payload");
        assertTrue(payload.contains("tok-1"), "the routing credential survives the edit");
        assertFalse(payload.contains("\"value\""), "values never enter the payload snapshot");
        JsonNode entries = mapper.readTree(payload).path("entries");
        assertEquals(2, entries.size(), "unticked candidates stay on the snapshot");
        assertFalse(entries.get(0).path("selected").asBoolean(true));
        assertTrue(entries.get(1).path("selected").asBoolean(false));
    }

    @Test
    void replayOfTheCurrentSetWritesNothing() {
        givenDraft(SNAPSHOT, 1);
        int checked = service.updateCheckedSubset(9L, 900L, List.of("SESSION"));
        assertEquals(1, checked);
        verify(named, never()).update(anyString(), any(MapSqlParameterSource.class));
    }

    @Test
    void concurrentStatusFlipRefusesInsteadOfSilentlyLosingTheEdit() {
        givenDraft(SNAPSHOT, 1);
        when(named.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(0);
        ServiceException error = assertThrows(ServiceException.class,
                () -> service.updateCheckedSubset(9L, 900L, List.of("theme")));
        assertTrue(error.getMessage().contains("勾选清单未保存"), error.getMessage());
    }

    @Test
    void draftWithoutACheckListIsRefusedHonestly() {
        givenDraft("{\"caseId\":5}", 1);
        ServiceException error = assertThrows(ServiceException.class,
                () -> service.updateCheckedSubset(9L, 900L, List.of("theme")));
        assertTrue(error.getMessage().contains("没有可勾选"), error.getMessage());
        verify(named, never()).update(anyString(), any(MapSqlParameterSource.class));
    }
}
