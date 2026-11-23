package com.sixbean.testpike.admin.cases.infrastructure.remote.client;

import com.sixbean.testpike.admin.cases.api.dto.FlowchartView;
import com.sixbean.testpike.admin.cases.api.dto.ScriptView;
import com.sixbean.testpike.admin.cases.api.dto.ScriptVersionDiffView;
import com.sixbean.testpike.admin.cases.api.dto.ScriptVersionView;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.common.client.RestClientInvoker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


@Component
public class RestCoreScriptClient implements CoreScriptClient {

    private final RestClient rest;

    public RestCoreScriptClient(@Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public ScriptView getScript(long caseId) {
        return RestClientInvoker.call(rest, HttpMethod.GET, "/internal/scripts/" + caseId, null,
                new ParameterizedTypeReference<>() {
                }, null);
    }

    @Override
    public long regenerateScript(long caseId, long projectId, long operatorId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("projectId", projectId);
        body.put("taskType", 2);
        body.put("caseId", caseId);
        Map<String, Long> result = RestClientInvoker.call(rest, HttpMethod.POST,
                "/internal/ai-tasks", body, new ParameterizedTypeReference<>() {
                }, operatorId);
        return result.get("taskId");
    }

    @Override
    public List<ScriptVersionView> listVersions(long caseId) {
        return RestClientInvoker.call(rest, HttpMethod.GET,
                "/internal/scripts/" + caseId + "/versions", null,
                new ParameterizedTypeReference<>() {
                }, null);
    }

    @Override
    public ScriptVersionDiffView diffVersion(long caseId, int version, Integer baseVersion) {
        String uri = "/internal/scripts/" + caseId + "/versions/" + version + "/diff"
                + (baseVersion == null ? "" : "?baseVersion=" + baseVersion);
        return RestClientInvoker.call(rest, HttpMethod.GET, uri, null,
                new ParameterizedTypeReference<>() {
                }, null);
    }

    @Override
    public FlowchartView flowchart(long caseId) {
        return RestClientInvoker.call(rest, HttpMethod.GET,
                "/internal/scripts/" + caseId + "/flowchart", null,
                new ParameterizedTypeReference<>() {
                }, null);
    }

    @Override
    public ScriptView saveScript(long caseId, String schemaVersion, String actionJson,
                                 int generatedBy, Long operatorId, Integer expectedVersion) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("caseId", caseId);
        body.put("schemaVersion", schemaVersion);
        body.put("actionJson", actionJson);
        body.put("generatedBy", generatedBy);
        body.put("operatorId", operatorId);
        body.put("expectedVersion", expectedVersion);
        return RestClientInvoker.call(rest, HttpMethod.POST, "/internal/scripts", body,
                new ParameterizedTypeReference<>() {
                }, operatorId);
    }

    @Override
    public FlowchartView previewFlowchart(long caseId, String actionJson) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("actionJson", actionJson);
        return RestClientInvoker.call(rest, HttpMethod.POST,
                "/internal/scripts/" + caseId + "/flowchart/preview", body,
                new ParameterizedTypeReference<>() {
                }, UserContext.currentUserId());
    }
}
