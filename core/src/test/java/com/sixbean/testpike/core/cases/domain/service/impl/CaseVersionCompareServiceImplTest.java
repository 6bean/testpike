package com.sixbean.testpike.core.cases.domain.service.impl;

import com.sixbean.testpike.core.cases.api.dto.CaseVersionCompareInfo;
import com.sixbean.testpike.core.cases.api.dto.CaseVersionDiff;
import com.sixbean.testpike.core.cases.api.dto.CaseVersionFieldChange;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.cases.domain.service.CaseVersionCompareService;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Compare-card read service . Branch-level L2 in the
 * CaseRollbackDraftStrategyTest style: the shared CaseService is mocked here
 * ONLY to isolate the delegation/validation branches — the real version
 * chain (404 / corrupted snapshot / span truncation) is exercised live in the
 * verification window, never faked away.
 */
class CaseVersionCompareServiceImplTest {

    private final CaseService caseService = mock(CaseService.class);
    private final CaseVersionCompareService service = new CaseVersionCompareServiceImpl(caseService);

    @Test
    void delegatesToTheSharedDiffDoorWithTargetAndBaseInTheRightSlots() {
        CaseVersionDiff diff = new CaseVersionDiff(12L, 3, 7, false, List.of(
                new CaseVersionFieldChange("title", "测试目标", "a", "b")));
        when(caseService.diffVersion(12L, 7, 3)).thenReturn(diff);

        CaseVersionCompareInfo info = service.compare(12L, 3, 7);

        // "compare vA and vB": the first named version is the baseline
        // (?baseVersion= slot), the second is the path target.
        verify(caseService).diffVersion(12L, 7, 3);
        assertEquals(12L, info.caseId());
        assertEquals(3, info.fromVersion());
        assertEquals(7, info.toVersion());
        assertEquals(1, info.changedFieldCount());
        assertEquals("用例 #12 v3 → v7：1 处差异：测试目标", info.summary());
    }

    @Test
    void absentBaselineFallsThroughToTheLatestVersionDefaultOfTheSharedDoor() {
        when(caseService.diffVersion(12L, 5, null))
                .thenReturn(new CaseVersionDiff(12L, 9, 5, false, List.of()));

        CaseVersionCompareInfo info = service.compare(12L, null, 5);

        verify(caseService).diffVersion(12L, 5, null);
        assertEquals("用例 #12 的 v9 与 v5 内容完全一致", info.summary());
    }

    @Test
    void anchorsBelowOneAreRefusedInHumanWordsWithoutTouchingTheDoor() {
        assertThrows(ServiceException.class, () -> service.compare(0L, 3, 7));
        assertThrows(ServiceException.class, () -> service.compare(12L, 3, 0));
        assertThrows(ServiceException.class, () -> service.compare(12L, 0, 7));
        verifyNoInteractions(caseService);
    }

    @Test
    void refusalOfTheSharedDoorPropagatesUnchanged() {
        // 40401 missing version / 40001 corrupted snapshot both live inside
        // diffVersion; the compare card must not re-wrap or swallow them.
        when(caseService.diffVersion(anyLong(), anyInt(), any()))
                .thenThrow(new ServiceException("目标版本不存在", BizErrorCode.NOT_FOUND));

        ServiceException refused = assertThrows(ServiceException.class,
                () -> service.compare(12L, 3, 7));
        assertEquals("目标版本不存在", refused.getMessage());
    }
}
