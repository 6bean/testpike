package com.sixbean.testpike.admin.executor.api.controller;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.executor.api.dto.CaseExecHistoryView;
import com.sixbean.testpike.admin.executor.api.dto.ExecStepEvidenceView;
import com.sixbean.testpike.admin.executor.api.dto.ExecStepSnapshotView;
import com.sixbean.testpike.admin.executor.api.dto.ExecTaskView;
import com.sixbean.testpike.admin.executor.api.dto.ExecutorSelfCheckView;
import com.sixbean.testpike.admin.executor.api.dto.ExecutorView;
import com.sixbean.testpike.admin.executor.api.dto.ParallelWaterLevelView;
import com.sixbean.testpike.admin.executor.api.dto.PoolView;
import com.sixbean.testpike.admin.executor.api.dto.RunningTaskView;
import com.sixbean.testpike.admin.executor.api.dto.ScreenshotView;
import com.sixbean.testpike.admin.executor.infrastructure.remote.client.CoreExecClient;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;

import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;


@RestController
@RequestMapping("/api/v1/executors")
public class ExecutorController {

    private final CoreExecClient coreExecClient;
    private final ProjectAccessGuard guard;

    public ExecutorController(CoreExecClient coreExecClient, ProjectAccessGuard guard) {
        this.coreExecClient = coreExecClient;
        this.guard = guard;
    }

    
    @GetMapping
    @RequirePermission("perm:project:executor-pool")
    public Response<List<ExecutorView>> listExecutors() {
        return Response.builderSuccess(coreExecClient.listExecutors());
    }

    
    @GetMapping("/{executorId}/self-check")
    @RequirePermission("perm:project:executor-pool")
    public Response<ExecutorSelfCheckView> selfCheck(@PathVariable long executorId) {
        return Response.builderSuccess(coreExecClient.selfCheck(executorId));
    }

    
    @GetMapping("/{executorId}/tasks")
    @RequirePermission("perm:system:executor-log")
    public Response<Map<String, Object>> listExecutorTasks(@PathVariable long executorId,
                                                           @RequestParam(defaultValue = "all") String statusGroup,
                                                           @RequestParam(defaultValue = "1") int page,
                                                           @RequestParam(defaultValue = "10") int size,
                                                           @RequestParam(required = false) String sortBy,
                                                           @RequestParam(required = false) String sortDir) {
        return Response.builderSuccess(
                coreExecClient.listExecutorTasks(executorId, statusGroup, page, size, sortBy, sortDir));
    }

    
    @GetMapping("/tasks/{taskId}/logs")
    @RequirePermission("perm:system:executor-log")
    public Response<Map<String, String>> getTaskLog(@PathVariable long taskId) {
        ExecTaskView view = coreExecClient.getTask(taskId);
        guard.assertCanView(UserContext.current(), view.projectId());
        String log = coreExecClient.getTaskLog(taskId);
        return Response.builderSuccess(Map.of("log", log == null ? "" : log));
    }

    
    @GetMapping("/tasks")
    public Response<PageView<ExecTaskView>> listTasks(@RequestParam long projectId,
                                                      @RequestParam(defaultValue = "1") int page,
                                                      @RequestParam(defaultValue = "10") int size,
                                                      @RequestParam(required = false) String sortBy,
                                                      @RequestParam(required = false) String sortDir,
                                                      @RequestParam(required = false) Long batchId,
                                                      @RequestParam(required = false) String statusIn) {
        guard.assertCanView(UserContext.current(), projectId);
        Map<String, Object> data = coreExecClient.listTasks(projectId, page, size, sortBy, sortDir,
                batchId, statusIn);
        @SuppressWarnings("unchecked")
        List<ExecTaskView> list = (List<ExecTaskView>) data.getOrDefault("list", List.of());
        long total = ((Number) data.getOrDefault("total", 0)).longValue();
        return Response.builderSuccess(new PageView<>(list, total));
    }

    
    @GetMapping("/tasks/{taskId}")
    public Response<ExecTaskView> getTask(@PathVariable long taskId) {
        ExecTaskView view = coreExecClient.getTask(taskId);
        guard.assertCanView(UserContext.current(), view.projectId());
        return Response.builderSuccess(view);
    }

    
    @GetMapping("/tasks/{taskId}/screenshots")
    public Response<List<ScreenshotView>> listScreenshots(@PathVariable long taskId) {
        ExecTaskView view = coreExecClient.getTask(taskId);
        guard.assertCanView(UserContext.current(), view.projectId());
        return Response.builderSuccess(coreExecClient.listScreenshotUrls(taskId));
    }

    
    @GetMapping("/tasks/{taskId}/step-snapshots")
    public Response<List<ExecStepSnapshotView>> listStepSnapshots(@PathVariable long taskId) {
        ExecTaskView view = coreExecClient.getTask(taskId);
        guard.assertCanView(UserContext.current(), view.projectId());
        return Response.builderSuccess(coreExecClient.listStepSnapshots(taskId));
    }

 
    @GetMapping("/tasks/{taskId}/step-evidence")
    public Response<List<ExecStepEvidenceView>> listStepEvidence(@PathVariable long taskId) {
        ExecTaskView view = coreExecClient.getTask(taskId);
        guard.assertCanView(UserContext.current(), view.projectId());
        return Response.builderSuccess(coreExecClient.listStepEvidence(taskId));
    }

    
    @GetMapping("/tasks/{taskId}/monitor-frames")
    public Response<Map<String, Object>> readMonitorFrames(@PathVariable long taskId) {
        ExecTaskView view = coreExecClient.getTask(taskId);
        guard.assertCanView(UserContext.current(), view.projectId());
        return Response.builderSuccess(coreExecClient.readMonitorFrames(taskId));
    }

    
    @GetMapping("/cases/{caseId}/history")
    @RequirePermission("perm:batch:run-history")
    public Response<PageView<CaseExecHistoryView>> listCaseHistory(@PathVariable long caseId,
                                                                   @RequestParam long projectId,
                                                                   @RequestParam(defaultValue = "1") int page,
                                                                   @RequestParam(defaultValue = "10") int size) {
        guard.assertCanView(UserContext.current(), projectId);
        Map<String, Object> data = coreExecClient.listCaseHistory(caseId, projectId, page, size);
        @SuppressWarnings("unchecked")
        List<CaseExecHistoryView> list = (List<CaseExecHistoryView>) data.getOrDefault("list", List.of());
        long total = ((Number) data.getOrDefault("total", 0)).longValue();
        return Response.builderSuccess(new PageView<>(list, total));
    }

    
    @PostMapping("/tasks/{taskId}/cancel")
    @RequirePermission("perm:exec:task-cancel")
    public Response<Map<String, Object>> cancelTask(@PathVariable long taskId) {
        return Response.builderSuccess(
                coreExecClient.cancelTask(taskId, UserContext.currentUserId()));
    }

 
    @PostMapping("/tasks/batch-cancel")
    @RequirePermission("perm:exec:task-cancel")
    public Response<Map<String, Object>> batchCancel(@RequestBody List<Long> taskIds) {
        return Response.builderSuccess(
                coreExecClient.batchCancel(taskIds, UserContext.currentUserId()));
    }

 /** Running-process view (cross-batch executing-task list: case / batch /
 * executor / started / elapsed / budget columns).
 * <p>Optional sort pair : null/blank keeps the core historical
 * id ASC order; a whitelisted key orders the whole window server-side. */
    @GetMapping("/running")
    @RequirePermission("perm:exec:task-cancel")
    public Response<PageView<RunningTaskView>> listRunning(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDir) {
        Map<String, Object> data = coreExecClient.listRunning(page, size, sortBy, sortDir);
        @SuppressWarnings("unchecked")
        List<RunningTaskView> list = (List<RunningTaskView>) data.getOrDefault("list", List.of());
        long total = ((Number) data.getOrDefault("total", 0)).longValue();
        return Response.builderSuccess(new PageView<>(list, total));
    }

    
    @GetMapping("/parallel-water-level")
    @RequirePermission("perm:exec:task-cancel")
    public Response<ParallelWaterLevelView> parallelWaterLevel() {
        return Response.builderSuccess(coreExecClient.parallelWaterLevel());
    }

    
    @GetMapping("/pools")
    @RequirePermission("perm:project:executor-pool")
    public Response<List<PoolView>> listPools() {
        return Response.builderSuccess(coreExecClient.listPools());
    }
}
