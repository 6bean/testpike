package com.sixbean.testpike.admin.executor.infrastructure.remote.client;

import com.sixbean.testpike.admin.executor.api.dto.BatchCaseRerunView;
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
import com.sixbean.testpike.common.client.RestClientInvoker;

import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


@Component
public class RestCoreExecClient implements CoreExecClient {

    private final RestClient rest;

    public RestCoreExecClient(@Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public long createTask(long projectId, long caseId, String schemaVersion, String actionJson,
                           Long envId, long operatorId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("projectId", projectId);
        body.put("caseId", caseId);
        body.put("schemaVersion", schemaVersion);
        body.put("actionJson", actionJson);
        body.put("envId", envId);
        Map<String, Long> result = RestClientInvoker.call(rest, HttpMethod.POST,
                "/internal/executors/tasks", body, new ParameterizedTypeReference<>() {
                }, operatorId);
        return result.get("taskId");
    }

    @Override
    public List<ExecutorView> listExecutors() {
        return RestClientInvoker.call(rest, HttpMethod.GET, "/internal/executors", null,
                new ParameterizedTypeReference<Response<List<ExecutorView>>>() {
                }, null);
    }

    @Override
    public ExecutorSelfCheckView selfCheck(long executorId) {
        return RestClientInvoker.call(rest, HttpMethod.GET,
                "/internal/executors/" + executorId + "/self-check", null,
                new ParameterizedTypeReference<Response<ExecutorSelfCheckView>>() {
                }, null);
    }

    @Override
    public Map<String, Object> listTasks(long projectId, int page, int size,
                                         String sortBy, String sortDir,
                                         Long batchId, String statusIn) {
 // optional sort pair: null/blank stays absent (core keeps the historical order),
 // non-empty is url-encoded and appended (same posture as rerunBatchCase engineCode)
        StringBuilder uri = new StringBuilder("/internal/executors/tasks?projectId=")
                .append(projectId).append("&page=").append(page).append("&size=").append(size);
        appendIfPresent(uri, "sortBy", sortBy);
        appendIfPresent(uri, "sortDir", sortDir);
        appendIfPresent(uri, "batchId", batchId == null ? null : String.valueOf(batchId));
        appendIfPresent(uri, "statusIn", statusIn);
        return RestClientInvoker.call(rest, HttpMethod.GET, uri.toString(),
                null, new ParameterizedTypeReference<>() {
                }, null);
    }

 /** Append a url-encoded query param only when the value carries content. */
    private static void appendIfPresent(StringBuilder uri, String name, String value) {
        if (value != null && !value.isBlank()) {
            uri.append('&').append(name).append('=')
                    .append(java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    @Override
    public ExecTaskView getTask(long taskId) {
        Map<String, Object> raw = RestClientInvoker.call(rest, HttpMethod.GET,
                "/internal/executors/tasks/" + taskId, null, new ParameterizedTypeReference<>() {
                }, null);
        return mapTask(raw);
    }

    @Override
    public BatchCaseRerunView rerunBatchCase(long taskId, long operatorId, String engineCode) {
        String uri = "/internal/executors/tasks/" + taskId + "/rerun"
                + (engineCode == null || engineCode.isBlank() ? ""
                        : "?engineCode=" + java.net.URLEncoder.encode(engineCode,
                                java.nio.charset.StandardCharsets.UTF_8));
        return RestClientInvoker.call(rest, HttpMethod.POST, uri, null,
                new ParameterizedTypeReference<Response<BatchCaseRerunView>>() {
                }, operatorId);
    }

    @Override
    public List<ScreenshotView> listScreenshotUrls(long taskId) {
        return RestClientInvoker.call(rest, HttpMethod.GET,
                "/internal/executors/tasks/" + taskId + "/screenshots", null,
                new ParameterizedTypeReference<Response<List<ScreenshotView>>>() {
                }, null);
    }

    @Override
    public List<ExecStepSnapshotView> listStepSnapshots(long taskId) {
        return RestClientInvoker.call(rest, HttpMethod.GET,
                "/internal/executors/tasks/" + taskId + "/step-snapshots", null,
                new ParameterizedTypeReference<Response<List<ExecStepSnapshotView>>>() {
                }, null);
    }

    @Override
    public List<ExecStepEvidenceView> listStepEvidence(long taskId) {
        return RestClientInvoker.call(rest, HttpMethod.GET,
                "/internal/executors/tasks/" + taskId + "/step-evidence", null,
                new ParameterizedTypeReference<Response<List<ExecStepEvidenceView>>>() {
                }, null);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> readMonitorFrames(long taskId) {
        Map<String, Object> data = RestClientInvoker.call(rest, HttpMethod.GET,
                "/internal/executors/tasks/" + taskId + "/monitor-frames", null,
                new ParameterizedTypeReference<Response<Map<String, Object>>>() {
                }, null);
        return data == null ? Map.of() : data;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> listCaseHistory(long caseId, long projectId, int page, int size) {
        Map<String, Object> data = RestClientInvoker.call(rest, HttpMethod.GET,
                "/internal/executors/cases/" + caseId + "/history?projectId=" + projectId
                        + "&page=" + page + "&size=" + size,
                null, new ParameterizedTypeReference<>() {
                }, null);
        List<Map<String, Object>> raw = (List<Map<String, Object>>) data.getOrDefault("list", List.of());
        List<CaseExecHistoryView> list = raw.stream().map(this::mapHistory).toList();
        data.put("list", list);
        return data;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> listExecutorTasks(long executorId, String statusGroup, int page, int size,
                                                 String sortBy, String sortDir) {
        StringBuilder uri = new StringBuilder("/internal/executors/" + executorId + "/tasks?statusGroup=")
                .append(statusGroup).append("&page=").append(page).append("&size=").append(size);
        // optional sort pair: null/blank stays absent (core keeps the historical order)
        appendIfPresent(uri, "sortBy", sortBy);
        appendIfPresent(uri, "sortDir", sortDir);
        Map<String, Object> data = RestClientInvoker.call(rest, HttpMethod.GET, uri.toString(),
                null, new ParameterizedTypeReference<>() {
                }, null);
        List<Map<String, Object>> raw = (List<Map<String, Object>>) data.getOrDefault("list", List.of());
        List<ExecTaskView> list = raw.stream().map(this::mapTask).toList();
        data.put("list", list);
        return data;
    }

    @Override
    public String getTaskLog(long taskId) {
        Map<String, String> data = RestClientInvoker.call(rest, HttpMethod.GET,
                "/internal/executors/tasks/" + taskId + "/logs", null,
                new ParameterizedTypeReference<Response<Map<String, String>>>() {
                }, null);
        return data.get("log");
    }

    @Override
    public Map<String, Object> cancelTask(long taskId, long operatorId) {
        return RestClientInvoker.call(rest, HttpMethod.POST,
                "/internal/executors/tasks/" + taskId + "/cancel", null,
                new ParameterizedTypeReference<>() {
                }, operatorId);
    }

    @Override
    public Map<String, Object> batchCancel(List<Long> taskIds, long operatorId) {
        return RestClientInvoker.call(rest, HttpMethod.POST,
                "/internal/executors/tasks/batch-cancel", taskIds,
                new ParameterizedTypeReference<>() {
                }, operatorId);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> listRunning(int page, int size, String sortBy, String sortDir) {
        StringBuilder uri = new StringBuilder("/internal/executors/tasks/running?page=")
                .append(page).append("&size=").append(size);
        // optional sort pair: null/blank stays absent (core keeps the historical order)
        appendIfPresent(uri, "sortBy", sortBy);
        appendIfPresent(uri, "sortDir", sortDir);
        Map<String, Object> data = RestClientInvoker.call(rest, HttpMethod.GET, uri.toString(), null,
                new ParameterizedTypeReference<>() {
                }, null);
        List<Map<String, Object>> raw = (List<Map<String, Object>>) data.getOrDefault("list", List.of());
        List<RunningTaskView> list = raw.stream().map(m -> new RunningTaskView(
                ((Number) m.get("taskId")).longValue(),
                ((Number) m.get("projectId")).longValue(),
                (String) m.get("caseTitle"),
                m.get("batchId") == null ? null : ((Number) m.get("batchId")).longValue(),
                m.get("executorId") == null ? null : ((Number) m.get("executorId")).longValue(),
                (String) m.get("startedAt"),
                m.get("elapsedMs") == null ? 0 : ((Number) m.get("elapsedMs")).longValue(),
                m.get("timeoutMs") == null ? 0 : ((Number) m.get("timeoutMs")).longValue())).toList();
        data.put("list", list);
        return data;
    }

    @Override
    public ParallelWaterLevelView parallelWaterLevel() {
        return RestClientInvoker.call(rest, HttpMethod.GET,
                "/internal/executors/parallel-water-level", null,
                new ParameterizedTypeReference<Response<ParallelWaterLevelView>>() {
                }, null);
    }

    @Override
    public List<PoolView> listPools() {
        return RestClientInvoker.call(rest, HttpMethod.GET,
                "/internal/executors/pools", null,
                new ParameterizedTypeReference<Response<List<PoolView>>>() {
                }, null);
    }

    
    @SuppressWarnings("unchecked")
    private CaseExecHistoryView mapHistory(Map<String, Object> m) {
        List<Map<String, Object>> rawShots = (List<Map<String, Object>>) m.getOrDefault("screenshots", List.of());
        List<ScreenshotView> shots = rawShots.stream()
                .map(s -> new ScreenshotView(((Number) s.get("taskId")).longValue(),
                        (String) s.get("objectKey"), (String) s.get("url")))
                .toList();
        return new CaseExecHistoryView(
                ((Number) m.get("id")).longValue(),
                m.get("batchId") == null ? null : ((Number) m.get("batchId")).longValue(),
                (String) m.get("batchName"),
                m.get("batchType") == null ? null : ((Number) m.get("batchType")).intValue(),
                (String) m.get("envVersion"),
                ((Number) m.get("status")).intValue(),
                m.get("errorType") == null ? null : ((Number) m.get("errorType")).intValue(),
                (String) m.get("errorDetail"),
                m.get("aiFailureClass") == null ? null : ((Number) m.get("aiFailureClass")).intValue(),
                (String) m.get("aiFailureNote"),
                m.get("durationMs") == null ? null : ((Number) m.get("durationMs")).intValue(),
                (String) m.get("startedAt"), (String) m.get("finishedAt"), shots,
                (String) m.get("engineCode"));
    }

    @SuppressWarnings("unchecked")
    private ExecTaskView mapTask(Map<String, Object> m) {
        return new ExecTaskView(
                ((Number) m.get("id")).longValue(),
                ((Number) m.get("projectId")).longValue(),
                ((Number) m.get("caseId")).longValue(),
                m.get("executorId") == null ? null : ((Number) m.get("executorId")).longValue(),
                ((Number) m.get("status")).intValue(),
                (String) m.get("schemaVersion"),
                m.get("errorType") == null ? null : ((Number) m.get("errorType")).intValue(),
                (String) m.get("errorDetail"),
                m.get("aiFailureClass") == null ? null : ((Number) m.get("aiFailureClass")).intValue(),
                (String) m.get("aiFailureNote"),
                (String) m.get("stepsTimeline"),
                (String) m.get("screenshots"),
                m.get("durationMs") == null ? null : ((Number) m.get("durationMs")).intValue(),
                (String) m.get("log"),
                (String) m.get("resultExt"),
                (String) m.get("startedAt"),
                (String) m.get("finishedAt"),
                (String) m.get("createdAt"),
                // Badge pair passthrough (core single source; admin zero re-derivation):
                // taskType int, perfRunId nullable.
                m.get("taskType") == null ? 0 : ((Number) m.get("taskType")).intValue(),
                m.get("perfRunId") == null ? null : ((Number) m.get("perfRunId")).longValue());
    }
}
