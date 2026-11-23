package com.sixbean.testpike.mockserver.api.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.genkit.MockRequestContext;
import com.sixbean.testpike.common.mock.MockMatchEngine;
import com.sixbean.testpike.common.mock.MockMatchRequest;
import com.sixbean.testpike.common.mock.MockMatchTrace;
import com.sixbean.testpike.common.mock.MockMatchVerdict;
import com.sixbean.testpike.common.mq.MockHitRowMessage;
import com.sixbean.testpike.mockserver.domain.component.LlmFallbackClient;
import com.sixbean.testpike.mockserver.domain.component.LlmFallbackOutcome;
import com.sixbean.testpike.mockserver.domain.component.MockFaultDecision;
import com.sixbean.testpike.mockserver.domain.component.MockFaultInjector;
import com.sixbean.testpike.mockserver.domain.component.MockHitRecorder;
import com.sixbean.testpike.mockserver.domain.component.MockMissFingerprint;
import com.sixbean.testpike.mockserver.domain.component.MockLoadedStub;
import com.sixbean.testpike.mockserver.domain.component.MockRenderedResponse;
import com.sixbean.testpike.mockserver.domain.component.MockResponseRenderer;
import com.sixbean.testpike.mockserver.domain.component.MockScenarioMachine;
import com.sixbean.testpike.mockserver.domain.component.MockScenarioStateStore;
import com.sixbean.testpike.mockserver.domain.component.MockRecordingFrameRecorder;
import com.sixbean.testpike.mockserver.domain.component.ProxyForwardResult;
import com.sixbean.testpike.mockserver.domain.component.ProxyForwarder;
import com.sixbean.testpike.mockserver.domain.component.RuleLoader;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Mocked traffic entry: the pinned rule index plus the shared matching kernel
 * decide which stub answers, and the shared template kernel builds the body
 * the echo placeholder is replaced by the real verdict
 * chain). Every response still carries the pinned rule version header
 * (S-3 contract) so consumers can always tell which rule state answered.
 * <p>Honesty rules kept from the shell: no pin = 503 fail-closed; no rule
 * matches = 404 with the full adjudication trace (the "why didn't my mock
 * fire" answer is served right there); a broken response payload = a plain
 * error naming the problem, never a pretend-200.
 */
@RestController
public class MockTrafficController {

    /** Header naming the rule version that produced the response. */
    public static final String RULE_VERSION_HEADER = "X-TP-Mock-Rule-Version";
    /** Header telling whether the served pin is confirmed fresh or stale. */
    public static final String RULE_STALE_HEADER = "X-TP-Mock-Rule-Stale";
    /** Header echoing the lane the response was served on (allocated or reused). */
    public static final String MOCK_SESSION_HEADER = "X-TP-Mock-Session";
    /** Header telling which scenario state the served lane moved to. */
    public static final String MOCK_STATE_HEADER = "X-TP-Mock-State";
    /** Cookie carrying the auto-allocated lane for browser callers (C-5 ruling). */
    public static final String LANE_COOKIE = "tp_mock_session";

    /** Header telling the response came through the proxy from the real dependency. */
    public static final String FORWARD_HEADER = "X-TP-Mock-Forwarded";

    /**
 * Hard provenance label : {@code llm} rides ONLY a
 * model-generated fallback answer, so the caller, the ledger and later
 * reports can never mistake a paid AI guess for a stub or a real
 * dependency (X-TP-Mock-Source: llm red line).
 */
    public static final String MOCK_SOURCE_HEADER = "X-TP-Mock-Source";

    private final RuleLoader ruleLoader;
    private final MockMatchEngine matchEngine;
    private final MockResponseRenderer responseRenderer;
    private final MockHitRecorder hitRecorder;
    private final MockScenarioStateStore scenarioStates;
    private final ProxyForwarder proxyForwarder;
    private final MockRecordingFrameRecorder frameRecorder;
    private final MockFaultInjector faultInjector;
    /**
 * A-3 fallback rung of the miss ladder ; null keeps the rung
 * off (older wiring) — a plain miss then answers 404+trace as ever.
 */
    private final LlmFallbackClient llmFallbackClient;
    private final MockMissFingerprint fingerprints = new MockMissFingerprint();
    private final Duration scenarioTtl;
    private final ObjectMapper traceMapper = new ObjectMapper();

    public MockTrafficController(RuleLoader ruleLoader, MockMatchEngine matchEngine,
                                 MockResponseRenderer responseRenderer, MockHitRecorder hitRecorder,
                                 MockScenarioStateStore scenarioStates,
                                 ProxyForwarder proxyForwarder,
                                 MockRecordingFrameRecorder frameRecorder,
                                 MockFaultInjector faultInjector,
                                 LlmFallbackClient llmFallbackClient,
                                 @Value("${testpike.mock.scenario.ttl-minutes:120}") long scenarioTtlMinutes) {
        this.ruleLoader = ruleLoader;
        this.matchEngine = matchEngine;
        this.responseRenderer = responseRenderer;
        this.hitRecorder = hitRecorder;
        this.scenarioStates = scenarioStates;
        this.proxyForwarder = proxyForwarder;
        this.frameRecorder = frameRecorder;
        this.faultInjector = faultInjector;
        this.llmFallbackClient = llmFallbackClient;
        this.scenarioTtl = Duration.ofMinutes(scenarioTtlMinutes);
    }

    /**
     * Serve any path under /mocks/** once a rule pin is held. The servlet
     * response is a co-routine here, not decoration: the two wire-shape faults
     * (dribble/disconnect) must bytes on the socket the ResponseEntity path
     * cannot produce, so they write straight through this handle and the
     * handler returns {@code null} ("already answered").
     */
    @RequestMapping("/mocks/**")
    public ResponseEntity<Object> serve(HttpServletRequest request,
                                        HttpServletResponse servletResponse) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            // CORS preflight rides the plane without touching the kernel: it
            // must never consume a scenario step (browsers fire it invisibly)
            return ResponseEntity.noContent()
                    .header("Allow", "GET, POST, PUT, PATCH, DELETE, OPTIONS, HEAD")
                    .build();
        }
        if (!ruleLoader.everLoaded()) {
            // fail-closed: without a confirmed rule pin nothing is pretended to be mocked
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header(RULE_VERSION_HEADER, "unloaded")
                    .header(RULE_STALE_HEADER, "true")
                    .body(Map.of("mocked", false,
                            "reason", "no rule snapshot pinned yet — traffic plane stays closed without a pin"));
        }
        long startedAt = System.currentTimeMillis();
        String body = readBody(request);
        // Lane resolution runs before adjudication: every scenario decision —
        // candidate filter, state read, advance, ledger row — rides one value.
        Lane lane = resolveLane(request);
        MockMatchRequest matchRequest = toMatchRequest(request, body);
        Map<String, MockScenarioMachine> machines = ruleLoader.ruleIndex().scenarioMachines();
        Map<Long, MockLoadedStub> loadedById = new HashMap<>();
        for (MockLoadedStub stub : ruleLoader.ruleIndex().stubs()) {
            loadedById.put(stub.rule().stubId(), stub);
        }
        // Scenario pre-filter (single-dialect red line: the kernel stays
        // state-blind; membership/state gating happens on the candidate list)
        List<MockLoadedStub> candidates = new ArrayList<>();
        Map<Long, String> laneStates = new HashMap<>();
        List<MockMatchTrace> filteredTraces = new ArrayList<>();
        boolean storeFailure = false;
        for (MockLoadedStub stub : ruleLoader.ruleIndex().stubs()) {
            if (stub.scenarioName() == null || stub.scenarioName().isBlank()) {
                candidates.add(stub); // stateless rules are unconditional candidates
                continue;
            }
            MockScenarioMachine machine = machines.get(stub.scenarioName());
            if (machine == null) {
                filteredTraces.add(new MockMatchTrace(stub.rule().name(), "scenario-state", "FAIL",
                        "stub 属于场景「" + stub.scenarioName() + "」，但当前规则版本未装载该场景机器"));
                continue;
            }
            String current;
            try {
                current = scenarioStates.read(laneKey(stub.scenarioName(), machine, lane.value()));
            } catch (RuntimeException ex) {
                // state unknown = the rule cannot honestly fire: withhold it,
                // never guess the lane's position on the machine
                storeFailure = true;
                filteredTraces.add(new MockMatchTrace(stub.rule().name(), "scenario-state", "FAIL",
                        "仿真态存储读取失败，场景规则暂不服务"));
                continue;
            }
            String state = current == null ? machine.initialState() : current;
            String required = stub.requiredState();
            if (required != null && !required.isBlank() && !required.equals(state)) {
                filteredTraces.add(new MockMatchTrace(stub.rule().name(), "scenario-state", "FAIL",
                        "要求泳道处于状态「" + required + "」，当前为「" + state + "」"));
                continue;
            }
            laneStates.put(stub.rule().stubId(), state);
            candidates.add(stub);
        }
        // A dead store never guesses: scenario stubs were withheld above and
        // stateless rules kept serving. When even then nothing matches, the
        // honest answer is 503 — the miss may have been a scenario hit.
        MockMatchVerdict kernelVerdict = matchEngine.match(matchRequest,
                candidates.stream().map(MockLoadedStub::rule).toList());
        MockMatchVerdict verdict = withFilterTraces(kernelVerdict, filteredTraces);
        MockLoadedStub winner = verdict.matched() ? loadedById.get(verdict.stubId()) : null;
        String servedState = winner == null ? null : laneStates.get(winner.rule().stubId());
        if (!verdict.matched()) {
            if (storeFailure) {
                // fail-closed with plain words: with the state store down a
                // scenario miss cannot be told apart from a real no-match
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .header(RULE_VERSION_HEADER, ruleLoader.pinnedVersion())
                        .header(RULE_STALE_HEADER, String.valueOf(ruleLoader.isStale()))
                        .body(Map.of("mocked", false,
                                "reason", "仿真态存储不可用（Redis 读写失败），场景规则拒绝猜测当前状态"));
            }
            // Proxy rung of the miss ladder ii, gated by the
            // space policy since : passthrough always forwards when a
            // bound target exists; the default llm-fallback rung keeps the
            // pre-policy derived behaviour (forward only while a box is open);
            // reject never forwards. A forward while a box is open still
            // records one masked frame. Match order is untouched: stubs always win.
            Long boxId = ruleLoader.activeRecordingId();
            String target = ruleLoader.proxyTargetUrl();
            boolean policyForwards =
                    com.sixbean.testpike.common.mock.MockMissPolicies.PASSTHROUGH
                            .equals(ruleLoader.missPolicy())
                            ? target != null && !target.isBlank()
                            : boxId != null && target != null && !target.isBlank();
            if (com.sixbean.testpike.common.mock.MockMissPolicies.REJECT
                    .equals(ruleLoader.missPolicy())) {
                policyForwards = false;
            }
            if (policyForwards) {
                ResponseEntity<Object> forwarded =
                        forwardAndRecord(request, body, verdict, startedAt, lane, boxId, target);
                // Provenance honesty : a forwarded answer came
                // from the real dependency, not from the mock — the ledger row
                // must say "proxy" so the inspector's source label never paints
                // real traffic as a stubbed one.
                recordHit(request, body, verdict, startedAt, lane.value(),
                        forwarded.getStatusCode().value(), forwardedHeaders(forwarded),
                        responseBodyJson(forwarded.getBody()), null, "proxy");
                return withLane(forwarded, lane, null);
            }
            ResponseEntity<Object> response = notMatched(request, verdict);
            // AI rung of the miss ladder : only a PLAIN miss
            // escalates (scenario-store 503 and the proxy rung keep their
            // places above). core's answer door decides everything — gates,
            // budget, cache, generation; the data plane just asks once and
            // serves what comes back served. Every unserved shape (closed
            // gate, cold miss, leg trouble) falls through to the original
            // 404+trace below: the fallback helper's unhappiness never
            // becomes a dressed-up answer or a 500.
            if (com.sixbean.testpike.common.mock.MockMissPolicies.LLM_FALLBACK
                    .equals(ruleLoader.missPolicy())
                    && llmFallbackClient != null && ruleLoader.pinnedSpaceId() != null) {
                String relative = relativePath(request);
                LlmFallbackOutcome outcome = llmFallbackClient.answer(
                        ruleLoader.pinnedSpaceId(), request.getMethod(),
                        fingerprints.normalizePath(relative),
                        fingerprints.fingerprint(request.getMethod(), relative, body),
                        lane.value());
                if (outcome.served()) {
                    ResponseEntity<Object> answered = llmAnswered(outcome);
                    recordHit(request, body, verdict, startedAt, lane.value(),
                            answered.getStatusCode().value(), forwardedHeaders(answered),
                            responseBodyJson(answered.getBody()), null, "llm");
                    return withLane(answered, lane, null);
                }
            }
            recordHit(request, body, verdict, startedAt, lane.value(),
                    response.getStatusCode().value(), null, responseBodyJson(response.getBody()),
                    null);
            return withLane(response, lane, null);
        }
        String payload = ruleLoader.ruleIndex().responseJsonFor(verdict.stubId());
        MockRenderedResponse rendered;
        try {
            rendered = responseRenderer.render(payload, toContext(request, body, verdict, lane),
                    new ArrayList<>());
        } catch (ServiceException ex) {
            ResponseEntity<Object> response = renderFailure(request, verdict, ex);
            recordHit(request, body, verdict, startedAt, lane.value(),
                    response.getStatusCode().value(), null, responseBodyJson(response.getBody()),
                    null);
            return withLane(response, lane, servedState);
        }
        // Fault plane : one decision per served hit — the stub
        // payload's own fault key owns it entirely, else the space chaos dial
        // may claim the request. Miss and proxy paths above never get here:
        // injection only rides a stub answer, so attribution stays honest.
        JsonNode faultRoot = parsePayloadForFault(payload);
        MockFaultDecision fault;
        try {
            fault = faultInjector.decideFromPayload(faultRoot, ruleLoader.chaosPercent());
        } catch (ServiceException ex) {
            // fail-closed loudly: a half-configured fault refuses, never pretends
            ResponseEntity<Object> response = faultRefusal(request, verdict, ex);
            recordHit(request, body, verdict, startedAt, lane.value(),
                    response.getStatusCode().value(), null, responseBodyJson(response.getBody()),
                    null);
            return withLane(response, lane, servedState);
        }
        if (fault.applied()) {
            applyDelay(fault);
            if ("disconnect".equals(fault.kind())) {
                // No response plane at all — the ledger still tells the truth.
                // segment 4b L3 caught the old shape (a bare IOException thrown
                // while the response sat uncommitted let the container paint a
                // 500 error page: a pretend answer worse than the fault).
                recordHit(request, body, verdict, startedAt, lane.value(),
                        null, null, null, fault.kind());
                abortConnection(servletResponse, verdict, lane, rendered);
                return null;
            }
        }
        int servedStatus = fault.applied() && fault.statusOverride() > 0
                ? fault.statusOverride() : rendered.status();
        // Advance only on a genuinely served 2xx: a mock that answered 5xx or
        // missed must not consume the step the real system never completed.
        if (winner.newState() != null && !winner.newState().isBlank()
                && servedStatus >= 200 && servedStatus < 300
                && winner.scenarioName() != null) {
            MockScenarioMachine machine = machines.get(winner.scenarioName());
            if (machine != null && machine.declaredStates().contains(winner.newState())) {
                try {
                    scenarioStates.write(laneKey(winner.scenarioName(), machine, lane.value()),
                            winner.newState(), scenarioTtl);
                    servedState = winner.newState();
                } catch (RuntimeException ex) {
                    // the response stands (traffic was answered); the lane just
                    // did not move — the next request re-reads the old state
                }
            }
        }
        Object servedBody;
        if (fault.applied() && "dribble".equals(fault.kind())) {
            // Wire-level dribble L3): writing through the servlet
            // output stream makes the flush boundaries real on the socket; the
            // old ResponseEntity<StreamingResponseBody>-shaped body was
            // silently Jackson-serialized into "{}".
            String text = rendered.body();
            recordHit(request, body, verdict, startedAt, lane.value(),
                    servedStatus, rendered.headers(), text, fault.kind());
            mirrorHead(servletResponse, servedStatus, rendered.headers(), verdict, lane, servedState);
            writeDribbled(servletResponse.getOutputStream(), text, fault);
            return null;
        }
        servedBody = servedBodyFor(fault, rendered);
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(servedStatus)
                .header(RULE_VERSION_HEADER, ruleLoader.pinnedVersion())
                .header(RULE_STALE_HEADER, String.valueOf(ruleLoader.isStale()))
                .header("X-TP-Mock-Rule", verdict.stubName() == null ? "" : verdict.stubName());
        rendered.headers().forEach(builder::header);
        recordHit(request, body, verdict, startedAt, lane.value(),
                servedStatus, rendered.headers(),
                servedBody instanceof String text ? text : rendered.body(),
                fault.applied() ? fault.kind() : null);
        ResponseEntity<Object> served = builder.body(servedBody);
        return withLane(served, lane, servedState);
    }

    /** A lane handle: the value plus whether this request just got allocated. */
    private record Lane(String value, boolean allocated) {
    }

    /**
     * Lane precedence (M-c + C-5 rulings): X-TP-Mock-Session header wins, then
     * the tp_mock_session cookie, else a fresh UUID published via Set-Cookie so
     * plain browser flows get session isolation with zero setup.
     */
    private Lane resolveLane(HttpServletRequest request) {
        String header = request.getHeader(MOCK_SESSION_HEADER);
        if (header != null && !header.isBlank()) {
            return new Lane(header.trim(), false);
        }
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (LANE_COOKIE.equals(cookie.getName())
                        && cookie.getValue() != null && !cookie.getValue().isBlank()) {
                    return new Lane(cookie.getValue(), false);
                }
            }
        }
        return new Lane(UUID.randomUUID().toString(), true);
    }

    /** Publish the lane (header always; cookie only when freshly allocated). */
    private ResponseEntity<Object> withLane(ResponseEntity<Object> response, Lane lane, String state) {
        // Copy the served headers into a fresh writable container: the built
        // ResponseEntity exposes each value list as an unmodifiable view, so a
        // plain putAll would leave Set-Cookie (stub row) frozen and the lane
        // publish below would throw UnsupportedOperationException. Per-key
        // addAll into a new HttpHeaders keeps every value, in appearance order.
        HttpHeaders merged = new HttpHeaders();
        response.getHeaders().forEach((name, values) -> merged.addAll(name, new ArrayList<>(values)));
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(response.getStatusCode())
                .headers(merged);
        builder.header(MOCK_SESSION_HEADER, lane.value());
        if (state != null) {
            builder.header(MOCK_STATE_HEADER, state);
        }
        if (lane.allocated()) {
            builder.header("Set-Cookie", LANE_COOKIE + "=" + lane.value()
                    + "; Path=/; SameSite=Lax");
        }
        return builder.body(response.getBody());
    }

    /** Logical lane key: space | scenario | scope | lane (global scope shares one lane name). */
    private String laneKey(String scenarioName, MockScenarioMachine machine, String laneValue) {
        String laneSegment = "global".equals(machine.scope()) ? "global" : laneValue;
        return ruleLoader.pinnedSpaceKey() + "|" + scenarioName + "|"
                + machine.scope() + "|" + laneSegment;
    }

    /** Append the pre-filter trail to the kernel verdict so 404 answers "why". */
    private MockMatchVerdict withFilterTraces(MockMatchVerdict kernelVerdict,
                                              List<MockMatchTrace> extra) {
        if (extra.isEmpty()) {
            return kernelVerdict;
        }
        List<MockMatchTrace> traces = new ArrayList<>(extra);
        traces.addAll(kernelVerdict.traces());
        return new MockMatchVerdict(kernelVerdict.matched(), kernelVerdict.stubId(),
                kernelVerdict.stubName(), kernelVerdict.pathVars(), traces);
    }

    private void recordHit(HttpServletRequest request, String body,
                           MockMatchVerdict verdict, long startedAt, String lane,
                           Integer responseStatus, Map<String, String> responseHeaders,
                           String responseBody, String faultApplied) {
        recordHit(request, body, verdict, startedAt, lane, responseStatus,
                responseHeaders, responseBody, faultApplied, null);
    }

    /**
 * Stage one ledger row for an adjudicated request (hit or miss — the miss
 * row is exactly what the inspector needs). Recording never breaks the
 * response: a broken ledger path degrades the ledger, not the mock.
 * {@code source} is the honest provenance of the answering plane
 * (stub-side rows pass null = the plain miss/stub shape predating the
 * provenance label; an llm row must say so, .
 */
    private void recordHit(HttpServletRequest request, String body,
                           MockMatchVerdict verdict, long startedAt, String lane,
                           Integer responseStatus, Map<String, String> responseHeaders,
                           String responseBody, String faultApplied, String source) {
        try {
            hitRecorder.record(new MockHitRowMessage(request.getMethod(), relativePath(request),
                    firstQueryValues(parseQuery(request.getQueryString())), headerMap(request), body,
                    responseStatus, responseHeaders, responseBody,
                    verdict.matched(), verdict.matched() ? verdict.stubId() : null,
                    verdict.matched() ? verdict.stubName() : null,
                    tracesJson(verdict), (int) (System.currentTimeMillis() - startedAt),
                    lane, System.currentTimeMillis(), faultApplied, source));
        } catch (RuntimeException ex) {
            // the response already stands; ledger trouble shows up as degraded() on the inspector
        }
    }

    /** Serialize a synthesized error body for the ledger's response plane. */
    private String responseBodyJson(Object body) {
        try {
            return traceMapper.writeValueAsString(body);
        } catch (Exception ex) {
            // an unserializable answer body must not lose the row — the status still tells the story
            return null;
        }
    }

    /**
     * Forward the unmatched request through the proxy base and stage one
     * recorded frame for the open box. An answered upstream rides back with
     * its real status/headers/body; a transport failure becomes an honest 502
     * with plain words. Both outcomes produce a frame (null status = failure),
     * and neither can break the mock plane: recorder trouble degrades the
     * recording, never the response.
     */
    private ResponseEntity<Object> forwardAndRecord(HttpServletRequest request, String body,
                                                    MockMatchVerdict verdict, long startedAt,
                                                    Lane lane, Long boxId, String target) {
        ProxyForwardResult result = proxyForwarder.forward(request.getMethod(),
                relativePath(request), request.getQueryString(),
                headerMap(request), body, target);
        // Frames ride the cassette only while a box is actually open
        // passthrough forwards even with no recording running — the answer
        // stands, the frame stream honestly has nowhere to go).
        if (boxId != null) {
            try {
                frameRecorder.record(proxyForwarder.frameOf(request, relativePath(request), body,
                        result, ruleLoader.pinnedSpaceKey(), boxId));
            } catch (RuntimeException ex) {
                // the forwarded answer stands; the frame stream reports degradation on flush
            }
        }
        if (!result.answered()) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .header(RULE_VERSION_HEADER, ruleLoader.pinnedVersion())
                    .header(RULE_STALE_HEADER, String.valueOf(ruleLoader.isStale()))
                    .header(FORWARD_HEADER, "failed")
                    .body(Map.of("mocked", false,
                            "forwarded", true,
                            "reason", "当前规则未命中且代理转发失败：" + result.error(),
                            "path", request.getRequestURI()));
        }
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(result.status())
                .header(RULE_VERSION_HEADER, ruleLoader.pinnedVersion())
                .header(RULE_STALE_HEADER, String.valueOf(ruleLoader.isStale()))
                .header(FORWARD_HEADER, "true");
        result.headers().forEach(builder::header);
        return builder.body(result.body());
    }

    /** Ledger view of forwarded headers (null body-safe copy). */
    private static Map<String, String> forwardedHeaders(ResponseEntity<Object> response) {
        Map<String, String> out = new LinkedHashMap<>();
        response.getHeaders().forEach((k, v) -> out.put(k, v.isEmpty() ? "" : v.get(0)));
        return out;
    }

    /**
     * Shape one served fallback answer: its own status and headers, the
     * pinned-version attribution (the rule generation still rides every
     * response), and the hard {@code X-TP-Mock-Source: llm} label — an AI
     * guess must never wear a stub's or a dependency's clothes.
     */
    private ResponseEntity<Object> llmAnswered(LlmFallbackOutcome outcome) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(outcome.status())
                .header(RULE_VERSION_HEADER, ruleLoader.pinnedVersion())
                .header(RULE_STALE_HEADER, String.valueOf(ruleLoader.isStale()))
                .header(MOCK_SOURCE_HEADER, "llm");
        outcome.headers().forEach(builder::header);
        return builder.body(outcome.body());
    }

    /** 404 with the human-readable trail of why every evaluated rule missed. */
    private ResponseEntity<Object> notMatched(HttpServletRequest request,
                                              MockMatchVerdict verdict) {
        List<Map<String, String>> trace = verdict.traces().stream()
                .map(t -> Map.of("stub", t.stubName() == null ? "?" : t.stubName(),
                        "step", t.step(), "outcome", t.outcome(),
                        "detail", t.detail() == null ? "" : t.detail()))
                .toList();
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .header(RULE_VERSION_HEADER, ruleLoader.pinnedVersion())
                .header(RULE_STALE_HEADER, String.valueOf(ruleLoader.isStale()))
                .body(Map.of("mocked", false,
                        "reason", "当前规则版本下没有任何 stub 命中该请求（完整判定轨迹见 matchTrace）",
                        "path", request.getRequestURI(),
                        "matchTrace", trace));
    }

    /** A matched rule whose payload is broken must say so, not serve pretending. */
    private ResponseEntity<Object> renderFailure(HttpServletRequest request,
                                                 MockMatchVerdict verdict, ServiceException ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .header(RULE_VERSION_HEADER, ruleLoader.pinnedVersion())
                .header(RULE_STALE_HEADER, String.valueOf(ruleLoader.isStale()))
                .body(Map.of("mocked", false,
                        "reason", "命中规则「" + verdict.stubName() + "」的响应构造无法渲染：" + ex.getMessage(),
                        "stubId", verdict.stubId()));
    }

    /**
     * Fault counterpart of {@link #renderFailure}: the injector's fail-closed
     * refusal (unknown type, bad interval, non-5xx override) answers 500 with
     * plain words instead of serving a pretend-fault.
     */
    private ResponseEntity<Object> faultRefusal(HttpServletRequest request,
                                                MockMatchVerdict verdict, ServiceException ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .header(RULE_VERSION_HEADER, ruleLoader.pinnedVersion())
                .header(RULE_STALE_HEADER, String.valueOf(ruleLoader.isStale()))
                .body(Map.of("mocked", false,
                        "reason", "命中规则「" + verdict.stubName() + "」的故障配置无法执行：" + ex.getMessage(),
                        "stubId", verdict.stubId()));
    }

    /**
     * Second read of the served payload for the fault key. The renderer already
     * parsed (and validated) the document above, so a parse failure here can
     * only mean a drift between the two mappers: hand the decision as "none"
     * and let the already-rendered response stand — the renderer remains the
     * single honest word on payload shape (no duplicated error reporting).
     */
    private JsonNode parsePayloadForFault(String payload) {
        try {
            return traceMapper.readTree(payload);
        } catch (Exception ex) {
            return null;
        }
    }

    /** Hold the servlet thread for a delay fault (clamped upstream by the injector). */
    private static void applyDelay(MockFaultDecision fault) {
        if (!"delay".equals(fault.kind()) || fault.delayMs() <= 0) {
            return;
        }
        try {
            Thread.sleep(fault.delayMs());
        } catch (InterruptedException ex) {
            // restore the interrupt flag: the container owns this thread's lifecycle
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Shape the served body for the applied fault: malformed truncates the
     * JSON mid-document; dribble never reaches here (it writes through the
     * raw response). Everything else rides the rendered body untouched.
     */
    private static Object servedBodyFor(MockFaultDecision fault, MockRenderedResponse rendered) {
        if (!fault.applied()) {
            return rendered.body();
        }
        return switch (fault.kind()) {
            case "malformed" -> {
                String body = rendered.body();
                // cut mid-document and leave a dangling opening quote: strict
                // parsers and lenient ones (Jackson closes trailing tokens
                // automatically) must both reject what the client receives
                yield body.isEmpty() ? "" : body.charAt(0) + "\"";
            }
            default -> rendered.body();
        };
    }

    /**
     * Mirror the response head (status, rule-version attribution, stub
     * headers, lane publication) onto the raw servlet response. The single
     * shared step both wire-shape faults take before touching the body —
     * dribble and disconnect reuse it, no second copy of the header story.
     */
    private void mirrorHead(HttpServletResponse response, int status,
                            Map<String, String> stubHeaders, MockMatchVerdict verdict,
                            Lane lane, String state) {
        response.setStatus(status);
        response.setHeader(RULE_VERSION_HEADER, ruleLoader.pinnedVersion());
        response.setHeader(RULE_STALE_HEADER, String.valueOf(ruleLoader.isStale()));
        response.setHeader("X-TP-Mock-Rule", verdict.stubName() == null ? "" : verdict.stubName());
        if (stubHeaders != null) {
            stubHeaders.forEach((name, value) ->
                    response.setHeader(name, value == null ? "" : value));
        }
        response.setHeader(MOCK_SESSION_HEADER, lane.value());
        if (state != null) {
            response.setHeader(MOCK_STATE_HEADER, state);
        }
        if (lane.allocated()) {
            response.addHeader("Set-Cookie", LANE_COOKIE + "=" + lane.value()
                    + "; Path=/; SameSite=Lax");
        }
    }

    /**
     * Write the body in paced chunks, flushing every frame boundary so the
     * dribble cadence is real on the socket (chunk flushes are what a slow
     * peer suffers in production). An interrupt restores the flag and stops
     * pacing — the container owns this thread's lifecycle.
     */
    private static void writeDribbled(OutputStream out, String body, MockFaultDecision fault)
            throws IOException {
        byte[] raw = body.getBytes(StandardCharsets.UTF_8);
        int chunk = fault.chunkBytes() <= 0 ? Math.max(1, raw.length) : fault.chunkBytes();
        for (int sent = 0; sent < raw.length; sent += chunk) {
            int end = Math.min(raw.length, sent + chunk);
            out.write(raw, sent, end - sent);
            out.flush();
            if (fault.chunkDelayMs() > 0 && end < raw.length) {
                try {
                    Thread.sleep(fault.chunkDelayMs());
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /**
 * Cut the connection mid-transaction: the head is committed with a
 * promised Content-Length, then the stream closes with the body absent —
 * Tomcat detects the incomplete transaction and aborts, so the peer sees
 * headers followed by a dead socket instead of a container error page.
 * Honest wording: the call is aborted <em>before the body</em>, not
 * "zero bytes ever sent" L3 caught the old IOException-throw
 * shape being rewritten into a pretend 500 answer).
 */
    private void abortConnection(HttpServletResponse response, MockMatchVerdict verdict,
                                 Lane lane, MockRenderedResponse rendered) throws IOException {
        mirrorHead(response, rendered.status(), rendered.headers(), verdict, lane, null);
        response.setIntHeader("Content-Length", rendered.body().getBytes(StandardCharsets.UTF_8).length);
        OutputStream out = response.getOutputStream();
        response.flushBuffer();
        out.close();
    }

    /** Ledger wants one readable value per key; multi-valued keys join with comma. */
    private static Map<String, String> firstQueryValues(Map<String, List<String>> query) {
        Map<String, String> out = new LinkedHashMap<>();
        query.forEach((k, v) -> out.put(k, v.size() == 1 ? v.get(0) : String.join(",", v)));
        return out;
    }

    /** The adjudication trail serialized for the ledger jsonb column. */
    private String tracesJson(MockMatchVerdict verdict) {
        try {
            return traceMapper.writeValueAsString(verdict.traces().stream()
                    .map(t -> Map.of("stub", t.stubName() == null ? "?" : t.stubName(),
                            "step", t.step(), "outcome", t.outcome(),
                            "detail", t.detail() == null ? "" : t.detail()))
                    .toList());
        } catch (Exception ex) {
            return "[]";
        }
    }

    /** Reduce the servlet request to the kernel shape (path below /mocks/{spaceKey}). */
    private MockMatchRequest toMatchRequest(HttpServletRequest request, String body) {
        return new MockMatchRequest(request.getMethod(), relativePath(request),
                parseQuery(request.getQueryString()), headerMap(request),
                cookieMap(request), body, request.getContentType());
    }

    /** Template-context view: same request, header keys lowercase, first query value per key. */
    private MockRequestContext toContext(HttpServletRequest request, String body,
                                         MockMatchVerdict verdict, Lane lane) {
        Map<String, String> headers = headerMap(request);
        // The resolved lane rides the context so {{mock.session}} echoes the
        // value the stepping actually used (cookie/allocated lanes have no header)
        headers.put(MOCK_SESSION_HEADER.toLowerCase(java.util.Locale.ROOT), lane.value());
        return new MockRequestContext(request.getMethod(), relativePath(request),
                verdict.pathVars(), parseQuery(request.getQueryString()),
                headers, body);
    }

    /** Strip the /mocks/{spaceKey} routing root so rules match on their own paths. */
    private String relativePath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String root = "/mocks/" + ruleLoader.pinnedSpaceKey();
        if (uri.equals(root)) {
            return "/";
        }
        return uri.startsWith(root + "/") ? uri.substring(root.length()) : uri;
    }

    /** Multi-valued query parsing without servlet body-consumption side effects. */
    static Map<String, List<String>> parseQuery(String queryString) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (queryString == null || queryString.isBlank()) {
            return out;
        }
        for (String pair : queryString.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key = decode(eq < 0 ? pair : pair.substring(0, eq));
            String value = eq < 0 ? "" : decode(pair.substring(eq + 1));
            out.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
        }
        return out;
    }

    private static String decode(String raw) {
        try {
            return URLDecoder.decode(raw, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return raw;
        }
    }

    /** Case-insensitive lookups live in the kernel; here we hand over one value per header. */
    private static Map<String, String> headerMap(HttpServletRequest request) {
        Map<String, String> headers = new LinkedHashMap<>();
        var names = request.getHeaderNames();
        while (names != null && names.hasMoreElements()) {
            String name = names.nextElement();
            headers.put(name, request.getHeader(name));
        }
        return headers;
    }

    private static Map<String, String> cookieMap(HttpServletRequest request) {
        Map<String, String> cookies = new LinkedHashMap<>();
        Cookie[] incoming = request.getCookies();
        if (incoming != null) {
            for (Cookie cookie : incoming) {
                cookies.put(cookie.getName(), cookie.getValue());
            }
        }
        return cookies;
    }

    private static String readBody(HttpServletRequest request) {
        // servlet input stream: read fully, empty payload = null (kernel treats
        // absent body separately from "" for jsonpath/form misses)
        try (var in = request.getInputStream()) {
            byte[] raw = in.readAllBytes();
            return raw.length == 0 ? null : new String(raw, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return null;
        }
    }
}
