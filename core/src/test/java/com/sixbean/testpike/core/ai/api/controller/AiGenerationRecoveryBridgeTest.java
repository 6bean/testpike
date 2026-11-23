package com.sixbean.testpike.core.ai.api.controller;

import com.sixbean.testpike.core.ai.api.dto.AiBatchInfo;
import com.sixbean.testpike.core.ai.api.dto.AiRequirementLiveInfo;
import com.sixbean.testpike.core.ai.api.dto.AiTaskInfo;
import com.sixbean.testpike.core.ai.domain.service.AiBatchService;
import com.sixbean.testpike.core.ai.domain.service.AiTaskService;
import com.sixbean.testpike.core.events.domain.service.AiGenPublisher;
import com.sixbean.testpike.common.clientobject.Response;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Bridge contract of the recovery doors (internal layer): the query
 * parameters arriving from admin must land on the service calls unchanged —
 * the csv id list parsed once, an absent/blank ids parameter answered as an
 * empty list without dialing the ledger, and the project id forwarded to the
 * latest-batch/latest-task reads. Persistence semantics ride the service
 * suite (AiGenerationRecoveryQueryTest); this lane pins only the wire form.
 */
class AiGenerationRecoveryBridgeTest {

    private final AiTaskService aiTaskService = mock(AiTaskService.class);
    private final AiBatchService aiBatchService = mock(AiBatchService.class);
    private final AiGenPublisher aiGenPublisher = mock(AiGenPublisher.class);
    private final AiTaskInternalController taskController =
            new AiTaskInternalController(aiTaskService, aiGenPublisher);
    private final AiBatchInternalController batchController =
            new AiBatchInternalController(aiBatchService, aiGenPublisher);

    @Test
    void latestDoorsForwardTheProjectSliceToTheServices() {
        AiBatchInfo batch = new AiBatchInfo(3L, 7L, 5L, 2, 1, 1, 0, 0, 0, null, List.of(), null);
        when(aiBatchService.findLatestByProject(7L)).thenReturn(batch);
        assertEquals(batch, batchController.latest(7L).getData(), "batch recovery door passes the aggregate through");

        AiTaskInfo task = new AiTaskInfo(9L, 7L, 5L, 1, 1, "找回单任务", null, null, null, null, null, 0, null, null, null, null);
        when(aiTaskService.findLatestSingle(7L)).thenReturn(task);
        assertEquals(task, taskController.latestSingle(7L).getData(), "single-task recovery door passes the row through");
    }

    @Test
    void liveByRequirementsParsesCsvIdsOnceAndStaysHonestOnEmpty() {
        List<AiRequirementLiveInfo> rows = List.of(new AiRequirementLiveInfo(21L, 31L, 12L, 2));
        when(aiTaskService.liveByRequirements(eq(7L), anyList())).thenReturn(rows);

        Response<List<AiRequirementLiveInfo>> res = taskController.liveByRequirements(7L, "21, 22,23");
        assertEquals(rows, res.getData());

        taskController.liveByRequirements(7L, null);
        taskController.liveByRequirements(7L, "  ");

        ArgumentCaptor<List<Long>> captor = ArgumentCaptor.forClass(List.class);
        verify(aiTaskService, org.mockito.Mockito.times(3)).liveByRequirements(eq(7L), captor.capture());
        assertEquals(List.of(21L, 22L, 23L), captor.getAllValues().get(0),
                "csv ids are trimmed and parsed in one pass");
        assertEquals(List.of(), captor.getAllValues().get(1), "absent ids parameter forwards an empty list");
        assertEquals(List.of(), captor.getAllValues().get(2), "blank ids parameter forwards an empty list");
    }
}
