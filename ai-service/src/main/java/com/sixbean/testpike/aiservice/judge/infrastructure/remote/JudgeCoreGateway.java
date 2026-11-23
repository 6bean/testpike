package com.sixbean.testpike.aiservice.judge.infrastructure.remote;

import com.sixbean.testpike.common.client.RestClientInvoker;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;


@Component
public class JudgeCoreGateway {

    private final RestClient rest;

    public JudgeCoreGateway(
            @org.springframework.beans.factory.annotation.Value(
                    "${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    
    public JudgeRunBrief getRun(long runId) {
        return call(HttpMethod.GET, "/internal/judge/runs/" + runId, null,
                new ParameterizedTypeReference<Response<JudgeRunBrief>>() {
                });
    }

    
    public List<JudgeSampleBrief> listSamples(String setName, int version) {
        String encoded = URLEncoder.encode(setName, StandardCharsets.UTF_8);
        List<JudgeSampleBrief> data = call(HttpMethod.GET,
                "/internal/judge/evalset/samples?setName=" + encoded + "&version=" + version,
                null,
                new ParameterizedTypeReference<Response<List<JudgeSampleBrief>>>() {
                });
        return data == null ? List.of() : data;
    }

    
    public void writeRecord(JudgeRecordWrite record) {
        call(HttpMethod.POST, "/internal/judge/records", record.toRequestBody(),
                new ParameterizedTypeReference<Response<Object>>() {
                });
    }

    
    public JudgeArtifactGrantBrief artifactGrant(long runId, long sampleId) {
        return call(HttpMethod.GET, "/internal/judge/runs/" + runId + "/artifact-grant"
                + "?sampleId=" + sampleId, null,
                new ParameterizedTypeReference<Response<JudgeArtifactGrantBrief>>() {
                });
    }

    
    public JudgeRuleVerdictBrief ruleCheck(List<Map<String, Object>> rules,
                                           String output, String expected) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("rules", rules == null ? List.of() : rules);
        body.put("output", output);
        body.put("expected", expected);
        return call(HttpMethod.POST, "/internal/judge/rule-check", body,
                new ParameterizedTypeReference<Response<JudgeRuleVerdictBrief>>() {
                });
    }

    private <T> T call(HttpMethod method, String uri, Object body,
                       ParameterizedTypeReference<Response<T>> type) {
        return RestClientInvoker.call(rest, method, uri, body, type, null);
    }
}
