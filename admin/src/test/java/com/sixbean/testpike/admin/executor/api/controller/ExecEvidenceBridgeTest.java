package com.sixbean.testpike.admin.executor.api.controller;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.executor.api.dto.ExecStepEvidenceView;
import com.sixbean.testpike.admin.executor.api.dto.ExecTaskView;
import com.sixbean.testpike.admin.executor.infrastructure.remote.client.CoreExecClient;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.rbac.app.component.PublicEndpointRegistry;
import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


class ExecEvidenceBridgeTest {

    private final CoreExecClient coreExecClient = mock(CoreExecClient.class);
    private final ProjectAccessGuard guard = mock(ProjectAccessGuard.class);
    private final ExecutorController controller = new ExecutorController(coreExecClient, guard);

    @AfterEach
    void clearUser() {
        UserContext.clear();
    }

    @Test
    void 证据读路径判权先取任务再assertCanView() {
        UserContext.set(new CurrentUser(7L, "u", "名", "部", List.of("TEST_LEAD"), 0L, null));
        when(coreExecClient.getTask(7L)).thenReturn(new ExecTaskView(
                7L, 42L, 1L, null, 3, "v2", null, null, null, null, null, null, null, null, null,
                null, null, null, 6, null));
        when(coreExecClient.listStepEvidence(7L)).thenReturn(List.of(
                new ExecStepEvidenceView(7L, 2, "app.tap",
                        "evid/shot.png", "http://s3/evid/shot.png?sig=1",
                        "evid/tree.json", "http://s3/evid/tree.json?sig=1",
                        null, null, "evid/clip.mp4", "http://s3/evid/clip.mp4?sig=1",
                        "evid/net.jsonl", "http://s3/evid/net.jsonl?sig=1",
                        "evid/perf.jsonl", "http://s3/evid/perf.jsonl?sig=1")));

        Response<List<ExecStepEvidenceView>> resp = controller.listStepEvidence(7L);

        assertEquals("0", resp.getCode(), "判权通过=正常返回");
        assertEquals(1, resp.getData().size());
        ExecStepEvidenceView row = resp.getData().get(0);
        assertEquals("app.tap", row.action());
        assertEquals("evid/shot.png", row.screenshotKey());
        assertTrue(row.screenshotUrl().contains("sig="), "预签名 URL 原样透传");
        assertNull(row.logTailKey(), "log_tail 缺件=null 透传（admin 侧禁补假值）");
        assertEquals("evid/clip.mp4", row.recordKey(), "第四联录屏切片 objectKey 原样透传");
        assertTrue(row.recordUrl().contains("clip.mp4"), "录屏预签名 URL 原样透传");
        assertEquals("evid/net.jsonl", row.netTimelineKey(), "第五联弱网观测时序 objectKey 原样透传");
        assertTrue(row.netTimelineUrl().contains("net.jsonl"), "观测时序预签名 URL 原样透传");
        assertEquals("evid/perf.jsonl", row.perfTimelineKey(), "第六联性能采样曲线 objectKey 原样透传");
        assertTrue(row.perfTimelineUrl().contains("perf.jsonl"), "采样曲线预签名 URL 原样透传");
        verify(guard).assertCanView(any(CurrentUser.class), eq(42L));
    }

    @Test
    void 任务不存在404透出且不触达证据查询() {
        when(coreExecClient.getTask(9L)).thenThrow(new ServiceException("执行任务不存在", 40401));

        assertThrows(ServiceException.class, () -> controller.listStepEvidence(9L));
        verify(coreExecClient, never()).listStepEvidence(anyLong());
    }

    @Test
    void 证据读路径登记豁免禁裸缺口() {
        assertTrue(PublicEndpointRegistry.isPublic("GET", "/api/v1/executors/tasks/7/step-evidence"),
                "step-evidence 读路径必须登记豁免（与 screenshots/step-snapshots 同面，禁裸缺口）");
    }
}
