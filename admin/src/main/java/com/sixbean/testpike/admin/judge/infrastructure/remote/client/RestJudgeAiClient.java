package com.sixbean.testpike.admin.judge.infrastructure.remote.client;

import com.sixbean.testpike.admin.judge.api.dto.QaGenEventView;
import com.sixbean.testpike.admin.judge.api.dto.QaGenJobView;
import com.sixbean.testpike.admin.judge.api.dto.QaGenRequestBody;
import com.sixbean.testpike.admin.judge.api.dto.QaGenRunView;
import com.sixbean.testpike.admin.judge.api.dto.QaGenSubmitView;
import com.sixbean.testpike.common.client.RestClientInvoker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;

/**
 * RestClient implementation of the admin to ai-service question-generation leg
 * (J6; same posture as RestKnowledgeAiClient: aiservice.base-url reuse, the call
 * logic goes through the shared RestClientInvoker so ai-service business errors
 * re-throw in their own codes instead of being swallowed).
 * <p>Question generation runs in two shapes: the grouped job plane streams
 * per-group results into tp_qa_gen_job and the wizard polls that row (the
 * server-side row plus its stall verdict is the run's fate authority — a run
 * whose row has been silent past twice this window answers failed, produced
 * items kept), while the legacy inline face is a synchronous per-group LLM
 * pass bounded by the judge.qa-gen cost gate. The old hard-coded 10-minute
 * read window was measured
 * insufficient on the live spectrum (document 9016: 642 chunks over 17 groups,
 * HttpTimeoutException at exactly 600.0s while ai-service was still working),
 * so the window is install-tunable by key now. Shipped default 1800s derives
 * from measured numbers: a healthy group leg costs 25-60s, 17 groups at the
 * gate ceiling need roughly 1020s, leaving a ~1.76x margin. Stalled providers
 * are bounded by the ai-service provider-side timeout, never by inflating this
 * forward window; the finite value keeps the E7 hang-protection semantics.
 */
@Component
public class RestJudgeAiClient implements JudgeAiClient {

    private final RestClient rest;
    private final Duration qaGenWindow;

    public RestJudgeAiClient(
            @Value("${aiservice.base-url:http://localhost:18084}") String aiBaseUrl,
            @Value("${ai.judge.qa-gen.forward-read-timeout-seconds:1800}") long forwardReadTimeoutSeconds) {
        this.qaGenWindow = Duration.ofSeconds(forwardReadTimeoutSeconds);
        this.rest = RestClientInvoker.defaultRestClient(aiBaseUrl, this.qaGenWindow);
    }

    /** Read window actually applied to the question-generation leg (contract-tested). */
    Duration qaGenForwardWindow() {
        return qaGenWindow;
    }

    @Override
    public QaGenSubmitView submit(Long projectId, Long documentId, String modelHint,
                                  Long operatorId) {
        return RestClientInvoker.call(rest, HttpMethod.POST, "/internal/judge/evalset/qa-gen-submit",
                new QaGenRequestBody(projectId, documentId, modelHint, operatorId),
                new ParameterizedTypeReference<>() {
                }, operatorId);
    }

    @Override
    public QaGenJobView jobStatus(Long documentId, Long operatorId) {
        return RestClientInvoker.call(rest, HttpMethod.GET,
                "/internal/judge/evalset/qa-gen-job?documentId=" + documentId, null,
                new ParameterizedTypeReference<>() {
                }, operatorId);
    }

    @Override
    public List<QaGenEventView> jobEvents(Long documentId, String runToken, long cursor,
                                          Integer limit, Long operatorId) {
        String uri = "/internal/judge/evalset/qa-gen-events?documentId=" + documentId
                + "&runToken=" + runToken + "&cursor=" + cursor
                + (limit == null || limit <= 0 ? "" : "&limit=" + limit);
        return RestClientInvoker.call(rest, HttpMethod.GET, uri, null,
                new ParameterizedTypeReference<>() {
                }, operatorId);
    }

    @Override
    public List<QaGenRunView> qaGenRuns(Long documentId, Integer limit, Long operatorId) {
        String uri = "/internal/judge/evalset/qa-gen-runs?documentId=" + documentId
                + (limit == null || limit <= 0 ? "" : "&limit=" + limit);
        return RestClientInvoker.call(rest, HttpMethod.GET, uri, null,
                new ParameterizedTypeReference<>() {
                }, operatorId);
    }
}
