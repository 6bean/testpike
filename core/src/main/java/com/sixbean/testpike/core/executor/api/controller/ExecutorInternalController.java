package com.sixbean.testpike.core.executor.api.controller;

import com.sixbean.testpike.core.audit.domain.component.OperatorContext;
import com.sixbean.testpike.core.appauto.domain.component.DeviceAutoIngestor;
import com.sixbean.testpike.core.appauto.domain.component.DevicePerfIngestor;
import com.sixbean.testpike.core.appauto.domain.component.MonitorFrameBuffer;
import com.sixbean.testpike.core.cluster.domain.component.ClusterComponentIngestor;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.executor.api.dto.ArtifactGrantView;
import com.sixbean.testpike.core.executor.api.dto.BatchCancelResult;
import com.sixbean.testpike.core.executor.api.dto.BatchCaseRerunResult;
import com.sixbean.testpike.core.executor.api.dto.CaseExecHistoryView;
import com.sixbean.testpike.core.executor.api.dto.ClaimTaskView;
import com.sixbean.testpike.core.executor.api.dto.ExecResultInput;
import com.sixbean.testpike.core.executor.api.dto.ExecStepEvidenceView;
import com.sixbean.testpike.core.executor.api.dto.ExecStepSnapshotView;
import com.sixbean.testpike.core.executor.api.dto.ExecTaskCreateInput;
import com.sixbean.testpike.core.executor.api.dto.ExecTaskInfo;
import com.sixbean.testpike.core.executor.api.dto.ExecutorInfo;
import com.sixbean.testpike.core.executor.api.dto.ExecutorRegisterInput;
import com.sixbean.testpike.core.executor.api.dto.ExecutorSelfCheckView;
import com.sixbean.testpike.core.executor.api.dto.ExecutorTaskPageView;
import com.sixbean.testpike.core.executor.api.dto.ParallelWaterLevelView;
import com.sixbean.testpike.core.executor.api.dto.PoolView;
import com.sixbean.testpike.core.executor.api.dto.RunningTaskView;
import com.sixbean.testpike.core.executor.api.dto.ScreenshotView;
import com.sixbean.testpike.core.executor.domain.component.ArtifactStorage;
import com.sixbean.testpike.core.executor.domain.component.NetProxyResidualIngestor;
import com.sixbean.testpike.core.executor.domain.component.PoolDispatchSupport;
import com.sixbean.testpike.core.executor.domain.service.ExecTaskService;
import com.sixbean.testpike.core.executor.domain.service.ExecutorService;
import com.sixbean.testpike.core.perf.domain.component.PerfPartialFrameIngestor;

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
@RequestMapping("/internal/executors")
public class ExecutorInternalController {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(ExecutorInternalController.class);

    private final ExecutorService executorService;
    private final ExecTaskService execTaskService;
    private final ArtifactStorage artifactStorage;
    private final PoolDispatchSupport poolDispatchSupport;
    private final MonitorFrameBuffer monitorFrameBuffer;
    private final ClusterComponentIngestor clusterComponentIngestor;
    private final NetProxyResidualIngestor netProxyResidualIngestor;
    private final DevicePerfIngestor devicePerfIngestor;
    private final DeviceAutoIngestor deviceAutoIngestor;
    private final PerfPartialFrameIngestor perfPartialFrameIngestor;

    public ExecutorInternalController(ExecutorService executorService,
                                      ExecTaskService execTaskService,
                                      ArtifactStorage artifactStorage,
                                      PoolDispatchSupport poolDispatchSupport,
                                      MonitorFrameBuffer monitorFrameBuffer,
                                      ClusterComponentIngestor clusterComponentIngestor,
                                      NetProxyResidualIngestor netProxyResidualIngestor,
                                      DevicePerfIngestor devicePerfIngestor,
                                      DeviceAutoIngestor deviceAutoIngestor,
                                      PerfPartialFrameIngestor perfPartialFrameIngestor) {
        this.executorService = executorService;
        this.execTaskService = execTaskService;
        this.artifactStorage = artifactStorage;
        this.poolDispatchSupport = poolDispatchSupport;
        this.monitorFrameBuffer = monitorFrameBuffer;
        this.clusterComponentIngestor = clusterComponentIngestor;
        this.netProxyResidualIngestor = netProxyResidualIngestor;
        this.devicePerfIngestor = devicePerfIngestor;
        this.deviceAutoIngestor = deviceAutoIngestor;
        this.perfPartialFrameIngestor = perfPartialFrameIngestor;
    }

    
    @PostMapping("/register")
    public Response<Map<String, Object>> register(@RequestBody ExecutorRegisterInput input) {
        long id = executorService.register(input);
        return Response.builderSuccess(Map.of(
                "ok", true, "executorId", id, "serverTime", java.time.LocalDateTime.now().toString()));
    }

 
    @PostMapping("/heartbeat")
    public Response<Map<String, Object>> heartbeat(@RequestParam long executorId,
                                                   @RequestBody(required = false) Map<String, Object> body) {
        int runningTasks = body == null ? 0
                : ((Number) body.getOrDefault("runningTasks", 0)).intValue();
        executorService.heartbeat(executorId, runningTasks);
        if (body != null && body.get("monitorFrames") instanceof List<?> frames && !frames.isEmpty()) {
            try {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> typed = (List<Map<String, Object>>) frames;
                monitorFrameBuffer.ingest(typed);
            } catch (RuntimeException e) {
                log.warn("监看帧入账失败（心跳主链零影响）: {}", e.getMessage());
            }
        }
        if (body != null && body.get("components") instanceof List<?> components
                && !components.isEmpty()) {
            try {
                clusterComponentIngestor.ingest(String.valueOf(executorId), components);
            } catch (RuntimeException e) {
                log.warn("部件版本台账入账失败（心跳主链零影响）: {}", e.getMessage());
            }
        }
        if (body != null && body.get("netProxyAudits") instanceof List<?> audits
                && !audits.isEmpty()) {
            try {
                netProxyResidualIngestor.ingest(audits);
            } catch (RuntimeException e) {
                log.warn("弱网代理残留对账消费失败（心跳主链零影响）: {}", e.getMessage());
            }
        }
        if (body != null && body.get("perfReports") instanceof List<?> perfReports
                && !perfReports.isEmpty()) {
            try {
                devicePerfIngestor.ingest(perfReports);
            } catch (RuntimeException e) {
                log.warn("移动端性能摘要消费失败（心跳主链零影响）: {}", e.getMessage());
            }
        }
        if (body != null && body.get("devices") instanceof List<?> devices
                && !devices.isEmpty()) {
            try {
                Object scope = body.get("deviceProjectScope");
                deviceAutoIngestor.ingest(devices, scope == null ? null : String.valueOf(scope));
            } catch (RuntimeException e) {
                log.warn("心跳设备自动建档消费失败（心跳主链零影响）: {}", e.getMessage());
            }
        }
 // perfPartial= trailing key for in-press partial frames : perf_worker
        // ships {runId,seq,frames} rows with heartbeats every flush window so the
        // live curve updates per second instead of waiting for the result channel.
        // Rows carry runId only — PerfPartialFrameIngestor reverse-looks-up the
        // owning project in tp_perf_run (misdelivery guard); ingestion gates reuse
        // the single PerfMetricService (terminal-state reject / second-anchor
        // idempotency), never a second rule set. Missing key/empty = zero calls
        // (old executors zero drift); a failed consume degrades to a named warning
        // — the heartbeat main chain is never dragged down (same posture as the
        // four keys above).
        if (body != null && body.get("perfPartial") instanceof List<?> partialRows
                && !partialRows.isEmpty()) {
            try {
                perfPartialFrameIngestor.ingest(partialRows);
            } catch (RuntimeException e) {
                log.warn("perf partial frames ingestion failed (heartbeat main "
                        + "chain unaffected): {}", e.getMessage());
            }
        }
        return Response.builderSuccess(Map.of(
                "ok", true, "serverTime", java.time.LocalDateTime.now().toString(),
                "actions", List.of(),
                "cancelTaskIds", execTaskService.pendingCancelsFor(executorId)));
    }

 
    @GetMapping("/tasks/{taskId}/monitor-frames")
    public Response<Map<String, Object>> readMonitorFrames(@PathVariable long taskId) {
        return Response.builderSuccess(monitorFrameBuffer.readFrames(taskId));
    }

    
    @PostMapping("/tasks/claim")
    public Response<Map<String, Object>> claim(@RequestBody Map<String, Object> body) {
        long executorId = ((Number) body.get("executorId")).longValue();
        ClaimTaskView view = execTaskService.claim(executorId);
        Map<String, Object> data = new java.util.HashMap<>();
        data.put("ok", true);
        data.put("task", view);
        return Response.builderSuccess(data);
    }

    
    @PostMapping("/tasks/result")
    public Response<Map<String, Object>> submitResult(@RequestBody ExecResultInput input) {
        execTaskService.submitResult(input);
        return Response.builderSuccess(Map.of("accepted", true, "needRetry", false, "flaky", false));
    }

    
    @PostMapping("/tasks/session-artifacts")
    public Response<Map<String, Object>> ingestSessionArtifacts(
            @RequestBody com.sixbean.testpike.core.executor.api.dto.SessionArtifactsInput input) {
        execTaskService.ingestSessionArtifacts(input);
        return Response.builderSuccess(Map.of("accepted", true));
    }

 
    @PostMapping("/tasks/artifact")
    public Response<ArtifactGrantView> grantArtifact(@RequestBody Map<String, Object> body) {
        long taskId = ((Number) body.get("taskId")).longValue();
        String kind = (String) body.getOrDefault("kind", "screenshot");
        String fileName = (String) body.getOrDefault("fileName", "step.png");
        return Response.builderSuccess(artifactStorage.grantUpload(taskId, kind, fileName));
    }

 
    @PostMapping("/tasks")
    public Response<Map<String, Long>> createTask(@RequestBody ExecTaskCreateInput input) {
        long taskId = execTaskService.create(input);
        return Response.builderSuccess(Map.of("taskId", taskId));
    }

    
    @GetMapping("/tasks/{taskId}")
    public Response<ExecTaskInfo> getTask(@PathVariable long taskId) {
        return Response.builderSuccess(execTaskService.get(taskId));
    }

    
    @PostMapping("/tasks/{taskId}/rerun")
    public Response<BatchCaseRerunResult> rerunBatchCase(@PathVariable long taskId,
                                                         @RequestParam(required = false) String engineCode) {
        return Response.builderSuccess(
                execTaskService.rerunBatchCase(taskId, OperatorContext.currentOperatorId(), engineCode));
    }

    
    @PostMapping("/tasks/{taskId}/cancel")
    public Response<Map<String, Object>> cancelTask(@PathVariable long taskId) {
        execTaskService.cancel(OperatorContext.currentOperatorId(), taskId);
        return Response.builderSuccess(Map.of("ok", true, "status", 7));
    }

 
    @PostMapping("/tasks/batch-cancel")
    public Response<BatchCancelResult> batchCancel(@RequestBody List<Long> taskIds) {
        return Response.builderSuccess(
                execTaskService.batchCancel(OperatorContext.currentOperatorId(), taskIds));
    }

 /** Running-process list (cross-batch status=2 window: case / batch /
 * executor / elapsed / budget columns).
 * <p>Optional sort pair : null/blank keeps the historical id ASC
 * order, a whitelisted key orders server-side. */
    @GetMapping("/tasks/running")
    public Response<PageResult<RunningTaskView>> listRunning(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDir) {
        return Response.builderSuccess(execTaskService.listRunning(page, size, sortBy, sortDir));
    }

 
    @GetMapping("/parallel-water-level")
    public Response<ParallelWaterLevelView> parallelWaterLevel() {
        return Response.builderSuccess(execTaskService.parallelWaterLevel());
    }

    
    @GetMapping("/tasks/{taskId}/screenshots")
    public Response<List<ScreenshotView>> listScreenshots(@PathVariable long taskId) {
        return Response.builderSuccess(execTaskService.listScreenshots(taskId));
    }

 
    @GetMapping("/tasks/{taskId}/step-snapshots")
    public Response<List<ExecStepSnapshotView>> listStepSnapshots(@PathVariable long taskId) {
        return Response.builderSuccess(execTaskService.listStepSnapshots(taskId));
    }

 
    @GetMapping("/tasks/{taskId}/step-evidence")
    public Response<List<ExecStepEvidenceView>> listStepEvidence(@PathVariable long taskId) {
        return Response.builderSuccess(execTaskService.listStepEvidence(taskId));
    }

    
    @GetMapping("/tasks")
    public Response<Map<String, Object>> listTasks(@RequestParam long projectId,
                                                   @RequestParam(defaultValue = "1") int page,
                                                   @RequestParam(defaultValue = "10") int size,
                                                   @RequestParam(required = false) String sortBy,
                                                   @RequestParam(required = false) String sortDir,
                                                   @RequestParam(required = false) Long batchId,
                                                   @RequestParam(required = false) String statusIn) {
        return Response.builderSuccess(Map.of(
                "list", execTaskService.listByProject(projectId, page, size, sortBy, sortDir, batchId, statusIn),
                "total", execTaskService.countByProject(projectId, batchId, statusIn)));
    }

 
    @GetMapping("/{executorId}/self-check")
    public Response<ExecutorSelfCheckView> selfCheck(@PathVariable long executorId) {
        return Response.builderSuccess(executorService.selfCheck(executorId));
    }

 /** Executor-dimension task list (K2 executor log: group counts + filtered list).
 * <p>Optional sort pair : null/blank keeps the historical id DESC
 * order, a whitelisted key orders server-side. */
    @GetMapping("/{executorId}/tasks")
    public Response<ExecutorTaskPageView> listExecutorTasks(@PathVariable long executorId,
                                                            @RequestParam(defaultValue = "all") String statusGroup,
                                                            @RequestParam(defaultValue = "1") int page,
                                                            @RequestParam(defaultValue = "10") int size,
                                                            @RequestParam(required = false) String sortBy,
                                                            @RequestParam(required = false) String sortDir) {
        return Response.builderSuccess(
                execTaskService.listByExecutor(executorId, statusGroup, page, size, sortBy, sortDir));
    }

 
    @GetMapping("/tasks/{taskId}/logs")
    public Response<Map<String, String>> getTaskLog(@PathVariable long taskId) {
        String log = execTaskService.getLog(taskId);
        return Response.builderSuccess(Map.of("log", log == null ? "" : log));
    }

    
    @GetMapping("/cases/{caseId}/history")
    public Response<PageResult<CaseExecHistoryView>> listCaseHistory(@PathVariable long caseId,
                                                                     @RequestParam long projectId,
                                                                     @RequestParam(defaultValue = "1") int page,
                                                                     @RequestParam(defaultValue = "10") int size) {
        return Response.builderSuccess(execTaskService.listHistoryByCase(caseId, projectId, page, size));
    }

    
    @GetMapping
    public Response<List<ExecutorInfo>> listExecutors() {
        return Response.builderSuccess(executorService.listAll());
    }

    
    @GetMapping("/pools")
    public Response<List<PoolView>> listPools() {
        return Response.builderSuccess(poolDispatchSupport.listPoolViews());
    }
}
