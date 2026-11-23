package com.sixbean.testpike.admin.judge.infrastructure.remote.client;

import com.sixbean.testpike.admin.judge.api.dto.EvalSampleView;
import com.sixbean.testpike.admin.judge.api.dto.EvalSetPublishBody;
import com.sixbean.testpike.admin.judge.api.dto.EvalSetVersionOptionView;
import com.sixbean.testpike.admin.judge.api.dto.EvalSetVersionView;
import com.sixbean.testpike.admin.judge.api.dto.GenerationSessionCardView;
import com.sixbean.testpike.admin.judge.api.dto.JudgeDispatchReceiptView;
import com.sixbean.testpike.admin.judge.api.dto.JudgeRubricOptionView;
import com.sixbean.testpike.admin.judge.api.dto.JudgeRunCreateBody;
import com.sixbean.testpike.admin.judge.api.dto.JudgeRunPageView;
import com.sixbean.testpike.admin.judge.api.dto.JudgeRunView;
import com.sixbean.testpike.admin.judge.api.dto.RunCostCardView;
import com.sixbean.testpike.admin.judge.api.dto.RunDiffView;
import com.sixbean.testpike.admin.judge.api.dto.RunSampleCardPageView;
import com.sixbean.testpike.admin.judge.api.dto.RunScorecardView;
import com.sixbean.testpike.admin.judge.api.dto.ScorecardTrendPointView;
import com.sixbean.testpike.common.client.RestClientInvoker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import org.springframework.web.util.UriComponentsBuilder;


@Component
public class RestCoreJudgeClient implements CoreJudgeClient {

    private final RestClient rest;

    public RestCoreJudgeClient(@Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public EvalSetVersionView publish(EvalSetPublishBody body) {
        return RestClientInvoker.call(rest, HttpMethod.POST, "/internal/judge/evalset/publish",
                body, new ParameterizedTypeReference<>() {
                }, body.createdBy());
    }

    @Override
    public RunSampleCardPageView sampleCards(long runId, int page, int size) {
        return RestClientInvoker.call(rest, HttpMethod.GET,
                "/internal/judge/runs/" + runId + "/sample-cards?page=" + page + "&size=" + size,
                null, new ParameterizedTypeReference<>() {
                }, null);
    }

    @Override
    public RunDiffView diffRuns(long a, long b, int page, int size,
                                String sortBy, String sortDir) {
        String uri = UriComponentsBuilder.fromPath("/internal/judge/runs/diff")
                .queryParam("a", a)
                .queryParam("b", b)
                .queryParam("page", page)
                .queryParam("size", size)
                .queryParamIfPresent("sortBy", Optional.ofNullable(sortBy).filter(s -> !s.isBlank()))
                .queryParamIfPresent("sortDir", Optional.ofNullable(sortDir).filter(s -> !s.isBlank()))
                .build().encode().toUriString();
        return RestClientInvoker.call(rest, HttpMethod.GET, uri,
                null, new ParameterizedTypeReference<>() {
                }, null);
    }

    @Override
    public RunCostCardView costCard(long runId) {
        return RestClientInvoker.call(rest, HttpMethod.GET,
                "/internal/judge/runs/" + runId + "/cost-card",
                null, new ParameterizedTypeReference<>() {
                }, null);
    }

    @Override
    public RunScorecardView scorecard(long runId) {
        return RestClientInvoker.call(rest, HttpMethod.GET,
                "/internal/judge/runs/" + runId + "/scorecard",
                null, new ParameterizedTypeReference<>() {
                }, null);
    }

    @Override
    public List<ScorecardTrendPointView> trend(String rubricName, String rubricDomain, int limit) {
        return RestClientInvoker.call(rest, HttpMethod.GET,
                "/internal/judge/scorecard/trend?rubricName=" + enc(rubricName)
                        + "&rubricDomain=" + enc(rubricDomain) + "&limit=" + limit,
                null, new ParameterizedTypeReference<>() {
                }, null);
    }

    @Override
    public List<GenerationSessionCardView> sessionCards(String rubricName, String rubricDomain,
                                                        int limit) {
        return RestClientInvoker.call(rest, HttpMethod.GET,
                "/internal/judge/scorecard/session-cards?rubricName=" + enc(rubricName)
                        + "&rubricDomain=" + enc(rubricDomain) + "&limit=" + limit,
                null, new ParameterizedTypeReference<>() {
                }, null);
    }

    @Override
    public List<EvalSampleView> samples(String setName, int version) {
        String uri = UriComponentsBuilder.fromPath("/internal/judge/evalset/samples")
                .queryParam("setName", setName)
                .queryParam("version", version)
                .build().encode().toUriString();
        return RestClientInvoker.call(rest, HttpMethod.GET, uri,
                null, new ParameterizedTypeReference<>() {
                }, null);
    }

    @Override
    public EvalSetVersionView raise(String setName, List<Long> sampleIds, Long createdBy) {
        return RestClientInvoker.call(rest, HttpMethod.POST, "/internal/judge/evalset/raise",
                java.util.Map.of("setName", setName, "sampleIds", sampleIds,
                        "createdBy", createdBy),
                new ParameterizedTypeReference<>() {
                }, createdBy);
    }

    @Override
    public EvalSetVersionView raiseFromBatch(String setName, List<Long> taskIds, Long createdBy) {
        return RestClientInvoker.call(rest, HttpMethod.POST,
                "/internal/judge/evalset/raise-from-batch",
                java.util.Map.of("setName", setName, "taskIds", taskIds,
                        "createdBy", createdBy),
                new ParameterizedTypeReference<>() {
                }, createdBy);
    }

    @Override
    public EvalSetVersionView raiseFromAdopted(String setName, List<Long> caseIds, Long createdBy) {
        return RestClientInvoker.call(rest, HttpMethod.POST,
                "/internal/judge/evalset/raise-from-adopted",
                java.util.Map.of("setName", setName, "caseIds", caseIds,
                        "createdBy", createdBy),
                new ParameterizedTypeReference<>() {
                }, createdBy);
    }

    @Override
    public JudgeRunView createRun(JudgeRunCreateBody body) {
        return RestClientInvoker.call(rest, HttpMethod.POST, "/internal/judge/runs",
                body, new ParameterizedTypeReference<>() {
                }, body.createdBy());
    }

    @Override
    public JudgeDispatchReceiptView dispatch(long runId, String evaluatedProvider,
                                             Long operatorId) {
        java.util.Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("runId", runId);
        payload.put("evaluatedProvider", evaluatedProvider);
        return RestClientInvoker.call(rest, HttpMethod.POST, "/internal/judge/dispatch",
                payload, new ParameterizedTypeReference<>() {
                }, operatorId);
    }

    @Override
    public JudgeRunPageView listRuns(String evalsetName, Integer status, int page, int size) {
        String uri = UriComponentsBuilder.fromPath("/internal/judge/runs")
                .queryParamIfPresent("evalsetName",
                        Optional.ofNullable(evalsetName).filter(s -> !s.isBlank()))
                .queryParamIfPresent("status", Optional.ofNullable(status))
                .queryParam("page", page)
                .queryParam("size", size)
                .build().encode().toUriString();
        return RestClientInvoker.call(rest, HttpMethod.GET, uri,
                null, new ParameterizedTypeReference<>() {
                }, null);
    }

    @Override
    public List<EvalSetVersionOptionView> listEvalsetVersions() {
        return RestClientInvoker.call(rest, HttpMethod.GET, "/internal/judge/evalsets",
                null, new ParameterizedTypeReference<>() {
                }, null);
    }

    @Override
    public List<JudgeRubricOptionView> listRubricFamilies() {
        return RestClientInvoker.call(rest, HttpMethod.GET, "/internal/judge/rubrics",
                null, new ParameterizedTypeReference<>() {
                }, null);
    }

    
    private static String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value.trim(), StandardCharsets.UTF_8);
    }
}
