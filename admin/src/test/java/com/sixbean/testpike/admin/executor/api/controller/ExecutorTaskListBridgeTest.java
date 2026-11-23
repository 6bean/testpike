package com.sixbean.testpike.admin.executor.api.controller;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.executor.api.dto.ExecTaskView;
import com.sixbean.testpike.admin.executor.infrastructure.remote.client.CoreExecClient;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Task-list admin-bridge contract test .
 * <p>Pins that the sort pair reaches core verbatim (no admin-side default
 * rewriting), that an absent sort arrives as null so core keeps the historical
 * newest-first order, and that the project segment + paging window stay untouched
 * beside the sort pair. Bridge L2 isolates the core client (NotificationListBridgeTest
 * precedent); the live admin-core chain is proven by the L3 probe script.
 */
class ExecutorTaskListBridgeTest {

    private final CoreExecClient coreExecClient = mock(CoreExecClient.class);
    private final ProjectAccessGuard guard = mock(ProjectAccessGuard.class);
    private final ExecutorController controller = new ExecutorController(coreExecClient, guard);

    @AfterEach
    void clearUser() {
        UserContext.clear();
    }

    private void login() {
        UserContext.set(new CurrentUser(7L, "u", "名", "部", List.of("TEST_LEAD"), 0L, null));
    }

 /** Sort pair forwarded as-is; the H1 view guard still runs on the project segment. */
    @Test
    void sortPairPassedThroughWithProjectGuard() {
        login();
        when(coreExecClient.listTasks(eq(5L), eq(1), eq(20),
                eq("duration_ms"), eq("asc"), isNull(), isNull()))
                .thenReturn(Map.of("list", List.of(), "total", 0L));

        controller.listTasks(5L, 1, 20, "duration_ms", "asc", null, null);

        verify(coreExecClient).listTasks(5L, 1, 20, "duration_ms", "asc", null, null);
        verify(guard).assertCanView(any(CurrentUser.class), eq(5L));
    }

 /** No sort given: core keeps the historical id DESC order (null pass-through). */
    @Test
    void absentSortReachesCoreAsNull() {
        login();
        when(coreExecClient.listTasks(anyLong(), anyInt(), anyInt(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(Map.of("list", List.of(), "total", 0L));

        controller.listTasks(5L, 1, 20, null, null, null, null);

        verify(coreExecClient).listTasks(5L, 1, 20, null, null, null, null);
    }

 /** Executor-tab bridge : the sort pair reaches core
 * verbatim through the executor-scoped endpoint too, and an absent pair
 * arrives as null so core keeps the historical newest-first order. */
    @Test
    void executorSortPairPassesThroughVerbatim() {
        when(coreExecClient.listExecutorTasks(eq(3L), eq("all"), eq(1), eq(10),
                eq("duration_ms"), eq("asc"))).thenReturn(Map.of("list", List.of(), "total", 0L));

        controller.listExecutorTasks(3L, "all", 1, 10, "duration_ms", "asc");

        verify(coreExecClient).listExecutorTasks(3L, "all", 1, 10, "duration_ms", "asc");
    }

    /** Running-process bridge : the optional sort pair for
 * the cross-batch running window passes through verbatim. */
    @Test
    void runningSortPairPassesThroughVerbatim() {
        when(coreExecClient.listRunning(eq(1), eq(20), eq("elapsed_ms"), eq("desc")))
                .thenReturn(Map.of("list", List.of(), "total", 0L));

        controller.listRunning(1, 20, "elapsed_ms", "desc");

        verify(coreExecClient).listRunning(1, 20, "elapsed_ms", "desc");
    }

    /** No sort on the running endpoint: core keeps id ASC (null pass-through). */
    @Test
    void runningAbsentSortReachesCoreAsNull() {
        when(coreExecClient.listRunning(2, 10, null, null))
                .thenReturn(Map.of("list", List.of(), "total", 0L));

        controller.listRunning(2, 10, null, null);

        verify(coreExecClient).listRunning(2, 10, null, null);
    }

 /** No sort on the executor endpoint: core keeps id DESC (null pass-through). */
    @Test
    void executorAbsentSortReachesCoreAsNull() {
        when(coreExecClient.listExecutorTasks(3L, "pending", 2, 20, null, null))
                .thenReturn(Map.of("list", List.of(), "total", 0L));

        controller.listExecutorTasks(3L, "pending", 2, 20, null, null);

        verify(coreExecClient).listExecutorTasks(3L, "pending", 2, 20, null, null);
    }

 /** Project segment and paging stay intact alongside the sort pair. */
    @Test
    void pagingAndProjectScopePassThroughUnchanged() {
        login();
        List<ExecTaskView> rows = List.of(
                new ExecTaskView(9L, 5L, 1L, null, 3, "v2", null, null, null, null,
                        null, null, 400, null, null, null, null, "2026-10-17 00:00:00",
                        1, null));
        when(coreExecClient.listTasks(5L, 2, 50, "status", "desc", null, null))
                .thenReturn(Map.of("list", rows, "total", 3L));

        PageView<ExecTaskView> page = controller.listTasks(5L, 2, 50, "status", "desc", null, null).getData();

        assertEquals(3L, page.total());
        assertEquals(1, page.list().size());
        verify(coreExecClient).listTasks(5L, 2, 50, "status", "desc", null, null);
    }
/** AW2 batch-promotion picker: the narrowing pair is forwarded verbatim,
     * still behind the H1 view guard — the bridge decides nothing itself. */
    @Test
    void batchAndStatusNarrowingPassedThroughBehindViewGuard() {
        login();
        when(coreExecClient.listTasks(eq(5L), eq(1), eq(200), isNull(), isNull(), eq(11L), eq("4,5")))
                .thenReturn(Map.of("list", List.of(), "total", 0L));

        controller.listTasks(5L, 1, 200, null, null, 11L, "4,5");

        verify(coreExecClient).listTasks(5L, 1, 200, null, null, 11L, "4,5");
        verify(guard).assertCanView(any(CurrentUser.class), eq(5L));
    }
}
