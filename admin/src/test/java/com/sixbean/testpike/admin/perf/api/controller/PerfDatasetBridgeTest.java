package com.sixbean.testpike.admin.perf.api.controller;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.perf.api.dto.PerfDatasetPageView;
import com.sixbean.testpike.admin.perf.infrastructure.remote.client.CorePerfDatasetClient;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Admin bridge contract test for the perf CSV dataset ledger .
 * <p>Asserted surface: ① all four dataset doors are project-manager gated
 * (assertCanManage — the data pool is a project-level asset consumed by the
 * launch wizard, same posture as the scenario ledger); ② the multipart upload
 * bytes/filename travel to core untouched and the new id comes back;
 * ③ the paged window (clamp, whitelisted order, truthful total) passes through
 * without the bridge re-sorting or re-paginating; ④ core's human-language
 * errors (row budget exceeded / cross-project id) propagate verbatim — no
 * second validation layer in admin (red line 6); ⑤ a non-manager never reaches
 * core at all.
 * <p>CorePerfDatasetClient/guard doubles = bridge-contract L2 isolation
 * (PerfGateBridgeTest precedent); the real admin→core→PG→object-storage chain
 * is the L3 live curl in this segment's close-out.
 */
class PerfDatasetBridgeTest {

    private final CorePerfDatasetClient client = mock(CorePerfDatasetClient.class);
    private final ProjectAccessGuard guard = mock(ProjectAccessGuard.class);
    private final PerfDatasetController controller = new PerfDatasetController(client, guard);

    @AfterEach
    void clearUser() {
        UserContext.clear();
    }

    private void login() {
        UserContext.set(new CurrentUser(7L, "u", "名", "部", List.of("TESTER"), 0L, null));
    }

    @Test
    void 上传数据集_管理闸按项目_字节与文件名原样下发() throws IOException {
        login();
        when(client.upload(eq(11L), eq("账号数据池"), eq("accounts.csv"), any(byte[].class)))
                .thenReturn(88L);
        MockMultipartFile file = new MockMultipartFile("file", "accounts.csv",
                "text/csv", "account_id\n1001\n".getBytes());
        assertEquals(88L, controller.upload(11L, "账号数据池", file).getData());
        verify(guard).assertCanManage(any(CurrentUser.class), eq(11L));
        verify(client).upload(11L, "账号数据池", "accounts.csv", file.getBytes());
    }

    @Test
    void 上传数据集_非管理者_core零下发() throws IOException {
        login();
        doThrow(new ServiceException("该操作仅项目负责人可执行", BizErrorCode.FORBIDDEN))
                .when(guard).assertCanManage(any(CurrentUser.class), anyLong());
        MockMultipartFile file = new MockMultipartFile("file", "a.csv", "text/csv",
                "x\n1\n".getBytes());
        assertThrows(ServiceException.class, () -> controller.upload(11L, "池", file));
        verify(client, never()).upload(anyLong(), any(), any(), any());
    }

    @Test
    void 分页列表_窗口与总数原样透出_桥不二次排序分页() {
        login();
        when(client.listPaged(11L, 2, 10, "updated_at", "asc", 0L))
                .thenReturn(new PerfDatasetPageView(
                        List.of(Map.of("id", 3L, "name", "池A")), 21L, 2, 10));
        PerfDatasetPageView page = controller.page(11L, 2, 10, "updated_at", "asc", 0L).getData();
        assertEquals(21L, page.total());
        assertEquals(2, page.page());
        assertEquals(1, page.list().size());
        verify(guard).assertCanManageWithinLens(any(CurrentUser.class), eq(11L), eq(0L));
    }

    /**
 * Program lens : the chosen program id is forwarded
 * verbatim to core and gated by the shared lens rule (project lens ∩ program
 * lens) — the bridge neither drops the lens nor re-filters the window.
 */
    @Test
    void 分页列表_节目透镜原样下发core_闸走透镜共享件() {
        login();
        when(client.listPaged(11L, 1, 20, null, null, 7L))
                .thenReturn(new PerfDatasetPageView(
                        List.of(Map.of("id", 4L, "name", "池B")), 3L, 1, 20));
        PerfDatasetPageView page = controller.page(11L, 1, 20, null, null, 7L).getData();
        assertEquals(3L, page.total());
        verify(client).listPaged(11L, 1, 20, null, null, 7L);
        verify(guard).assertCanManageWithinLens(any(CurrentUser.class), eq(11L), eq(7L));
    }

    @Test
    void 预览_首N行与列名随core透出() {
        login();
        when(client.view(11L, 88L)).thenReturn(Map.of(
                "id", 88L, "name", "账号数据池", "row_count", 5000,
                "columns", List.of("account_id"),
                "preview_rows", List.of(Map.of("account_id", "1001"))));
        Map<String, Object> view = controller.view(11L, 88L).getData();
        assertEquals(5000, view.get("row_count"));
        assertEquals(1, ((List<?>) view.get("preview_rows")).size());
        verify(client).view(11L, 88L);
    }

    @Test
    void core人话错误_原样透传_admin禁二套() {
        login();
        when(client.upload(anyLong(), any(), any(), any()))
                .thenThrow(new ServiceException("数据池行数 120000 超过当前上限 100000，请先在系统配置中调高上限",
                        BizErrorCode.VALIDATION_ERROR));
        MockMultipartFile file = new MockMultipartFile("file", "big.csv", "text/csv",
                "x\n1\n".getBytes());
        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.upload(11L, "大池", file));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("超过当前上限"));
    }

    @Test
    void 删除_管理闸先行_跨项目单据core人话404透传() {
        login();
        doThrow(new ServiceException("该数据池不存在或不属于当前项目，请刷新列表后重试", BizErrorCode.NOT_FOUND))
                .when(client).delete(11L, 99L);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.delete(11L, 99L));
        assertEquals(BizErrorCode.NOT_FOUND, ex.getCode());
        verify(guard).assertCanManage(any(CurrentUser.class), eq(11L));
    }

    @Test
    void 未登录_管理闸fail_fast_上传与删除零下发() throws IOException {
        MockMultipartFile file = new MockMultipartFile("file", "a.csv", "text/csv",
                "x\n1\n".getBytes());
        assertThrows(ServiceException.class, () -> controller.upload(11L, "池", file));
        assertThrows(ServiceException.class, () -> controller.delete(11L, 5L));
        verify(client, never()).upload(anyLong(), any(), any(), any());
        verify(client, never()).delete(anyLong(), anyLong());
    }
}
