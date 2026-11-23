package com.sixbean.testpike.core.aichat.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.aichat.api.dto.RehearsalRunReceipt;
import com.sixbean.testpike.core.batch.api.dto.BatchStartResult;
import com.sixbean.testpike.core.batch.domain.service.BatchService;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import com.sixbean.testpike.core.notification.domain.component.AdminAlertRecipients;
import com.sixbean.testpike.core.plans.domain.component.RehearsalExecGate;
import com.sixbean.testpike.core.project.domain.component.ProjectRoleResolver;
import com.sixbean.testpike.core.tool.domain.component.ToolAccessGate;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Transport + ordering contract of the rehearsal-run launch door (AO batch
 * : the dialog tool posts a batch id, the server re-checks the
 * identity, the project segment and the three-facet gate BEFORE the batch is
 * started, then mirrors the core start result into the receipt.
 * <p>Pinned faces: ① a missing or non-positive X-Operator-Id is refused before
 * any statement runs (fail-closed identity rule, same posture as the analysis
 * door); ② an unknown batch id is refused with a plain message and the start
 * chain is never touched; ③ a batch outside the operator's visible segment is
 * refused without consulting the run gate and without starting (horizontal
 * privilege never leaks, never executes); ④ a gate refusal propagates and
 * BatchService.start is provably never called (refusal precedes start); ⑤ the
 * happy-path receipt fields are the same-ticket direct transfer of
 * BatchStartResult plus the gate declaration lines and the progress path of
 * the batch detail page (the polling anchor).
 * <p>The real ToolAccessGate instance is wired over mocked resolvers so the
 * visibility face is exercised with its own code, same as the analysis door
 * spectrum; the run gate itself carries its own unit class.
 */
class RehearsalRunDoorTest {

    private static final long OPERATOR_ID = 9L;
    private static final long BATCH_ID = 7L;
    private static final long PROJECT_ID = 42L;

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final ProjectRoleResolver projectRoleResolver = mock(ProjectRoleResolver.class);
    private final AdminAlertRecipients adminAlertRecipients = mock(AdminAlertRecipients.class);
    private final ConfigService configService = mock(ConfigService.class);
    private final ToolAccessGate accessGate =
            new ToolAccessGate(projectRoleResolver, adminAlertRecipients, configService);
    private final RehearsalExecGate execGate = mock(RehearsalExecGate.class);
    private final BatchService batchService = mock(BatchService.class);
    private final RehearsalRunDoor door =
            new RehearsalRunDoor(jdbcTemplate, accessGate, execGate, batchService);

    private void stubBatchRow() {
        when(jdbcTemplate.queryForMap(anyString(), eq(BATCH_ID)))
                .thenReturn(Map.of("project_id", PROJECT_ID, "name", "演练回归批次"));
    }

    private void stubVisibleMember() {
        when(projectRoleResolver.isMember(PROJECT_ID, OPERATOR_ID)).thenReturn(true);
    }

    private Map<String, Object> body() {
        return Map.of("batchId", BATCH_ID);
    }

    @Test
    void refusesBeforeAnyReadWhenTheOperatorIdentityIsMissing() {
        assertThrows(ServiceException.class, () -> door.run(null, body()));
        verifyNoInteractions(jdbcTemplate, batchService);
        assertThrows(ServiceException.class, () -> door.run(0L, body()));
        assertThrows(ServiceException.class, () -> door.run(-1L, body()));
    }

    @Test
    void refusesAMissingBatchIdBodyWithoutTouchingTheStartChain() {
        assertThrows(ServiceException.class, () -> door.run(OPERATOR_ID, Map.of()));
        verify(batchService, never()).start(anyLong(), anyLong());
    }

    @Test
    void refusesAnUnknownBatchWithAPlainMessageAndNeverStarts() {
        when(jdbcTemplate.queryForMap(anyString(), eq(BATCH_ID)))
                .thenThrow(new EmptyResultDataAccessException(0));

        ServiceException e = assertThrows(ServiceException.class, () -> door.run(OPERATOR_ID, body()));
        assertTrue(e.getMessage().contains(String.valueOf(BATCH_ID)), e.getMessage());
        verify(batchService, never()).start(anyLong(), anyLong());
    }

    @Test
    void refusesACrossProjectBatchWithoutConsultingTheGateOrStarting() {
        stubBatchRow();
        when(projectRoleResolver.isMember(PROJECT_ID, OPERATOR_ID)).thenReturn(false);
        when(adminAlertRecipients.adminUserIds()).thenReturn(List.of());

        assertThrows(ServiceException.class, () -> door.run(OPERATOR_ID, body()));
        verify(execGate, never()).requireAllowed(anyLong());
        verify(batchService, never()).start(anyLong(), anyLong());
    }

    @Test
    void propagatesTheGateRefusalAndNeverStarts() {
        stubBatchRow();
        stubVisibleMember();
        when(execGate.requireAllowed(PROJECT_ID)).thenThrow(new ServiceException(
                "演练批次启动被拒绝：回归演练执行开关未打开", BizErrorCode.VALIDATION_ERROR));

        assertThrows(ServiceException.class, () -> door.run(OPERATOR_ID, body()));
        verify(batchService, never()).start(anyLong(), anyLong());
    }

    @Test
    void mirrorsTheStartResultIntoTheReceiptWithDeclarationsAndProgressPath() {
        stubBatchRow();
        stubVisibleMember();
        when(execGate.requireAllowed(PROJECT_ID)).thenReturn(List.of("申报一", "申报二", "申报三"));
        when(batchService.start(OPERATOR_ID, BATCH_ID)).thenReturn(new BatchStartResult(
                BATCH_ID, 3, "queued", "排队中", 2, "2026-10-31 10:30:00", List.of(), "批次已进入执行队列"));

        Response<RehearsalRunReceipt> response = door.run(OPERATOR_ID, body());
        RehearsalRunReceipt receipt = response.getData();

        assertEquals(BATCH_ID, receipt.batchId());
        assertEquals("queued", receipt.statusKey());
        assertEquals("排队中", receipt.statusLabel());
        assertEquals(2, receipt.queuePos().intValue());
        assertEquals("2026-10-31 10:30:00", receipt.estimatedStartAt());
        assertEquals(0, receipt.skipCount());
        assertEquals("批次已进入执行队列", receipt.message());
        assertEquals(List.of("申报一", "申报二", "申报三"), receipt.gateDeclarations());
        assertEquals("/batches/" + BATCH_ID, receipt.progressPath());
    }
}
