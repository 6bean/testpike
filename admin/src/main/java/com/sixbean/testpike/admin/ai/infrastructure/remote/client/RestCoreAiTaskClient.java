package com.sixbean.testpike.admin.ai.infrastructure.remote.client;

import com.sixbean.testpike.admin.ai.api.dto.AiRequirementLiveView;
import com.sixbean.testpike.admin.ai.api.dto.AiTaskEventView;
import com.sixbean.testpike.admin.ai.api.dto.AiTaskView;
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
public class RestCoreAiTaskClient implements CoreAiTaskClient {

    private final RestClient rest;

    public RestCoreAiTaskClient(@Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public Map<String, Long> createTask(long projectId, long operatorId, int taskType,
                                        String requirementText, Long requirementId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("projectId", projectId);
        body.put("taskType", taskType);
        body.put("requirementText", requirementText);
        body.put("requirementId", requirementId);
        return RestClientInvoker.call(rest, HttpMethod.POST, "/internal/ai-tasks", body,
                new ParameterizedTypeReference<>() {
                }, operatorId);
    }

    @Override
    public AiTaskView getTask(long taskId) {
        return RestClientInvoker.call(rest, HttpMethod.GET, "/internal/ai-tasks/" + taskId, null,
                new ParameterizedTypeReference<>() {
                }, null);
    }

    @Override
    public AiTaskView latestSingleTask(long projectId) {
        return RestClientInvoker.call(rest, HttpMethod.GET,
                "/internal/ai-tasks/latest-single?projectId=" + projectId, null,
                new ParameterizedTypeReference<>() {
                }, null);
    }

    @Override
    public List<AiRequirementLiveView> liveByRequirements(long projectId, List<Long> requirementIds) {
        if (requirementIds == null || requirementIds.isEmpty()) {
            return List.of();
        }
        String joined = requirementIds.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("");
        return RestClientInvoker.call(rest, HttpMethod.GET,
                "/internal/ai-tasks/live-by-requirements?projectId=" + projectId + "&ids=" + joined, null,
                new ParameterizedTypeReference<>() {
                }, null);
    }

    @Override
    public void retryTask(long taskId) {
        RestClientInvoker.call(rest, HttpMethod.POST, "/internal/ai-tasks/" + taskId + "/retry", null,
                new ParameterizedTypeReference<Response<Void>>() {
                }, null);
    }

    @Override
    public void cancelTask(long taskId) {
        RestClientInvoker.call(rest, HttpMethod.POST, "/internal/ai-tasks/" + taskId + "/cancel", null,
                new ParameterizedTypeReference<Response<Void>>() {
                }, null);
    }

    @Override
    public List<AiTaskEventView> taskProcessEvents(long taskId, long cursor, Integer limit) {
        String url = "/internal/ai-tasks/" + taskId + "/process-events?cursor=" + cursor
                + (limit == null || limit <= 0 ? "" : "&limit=" + limit);
        return RestClientInvoker.call(rest, HttpMethod.GET, url, null,
                new ParameterizedTypeReference<>() {
                }, null);
    }
}
