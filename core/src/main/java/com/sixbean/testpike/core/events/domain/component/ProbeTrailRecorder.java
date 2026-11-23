package com.sixbean.testpike.core.events.domain.component;

import com.sixbean.testpike.core.session.domain.service.LoginStateProbeService.Verdict;

import java.io.IOException;
import java.net.http.HttpTimeoutException;

/**
 * Thin per-door recorder over the shared {@link TaskEventWriter} ledger:
 * the two "test now" doors (login-state probe / credential test) tail the
 * same step &amp; error shapes with a different job kind, and that
 * difference is the only thing kept outside the single-source writer.
 * R40: doors must not each re-wrap the writer or trails drift - the
 * response-frame line, the transport-failure line and the verdict step
 * shared by both doors live here exactly once.
 */
public class ProbeTrailRecorder {

    private final TaskEventWriter events;
    private final String jobKind;
    private final ProbeExchangeRunner runner;

    public ProbeTrailRecorder(TaskEventWriter events, String jobKind, ProbeExchangeRunner runner) {
        this.events = events;
        this.jobKind = jobKind;
        this.runner = runner;
    }

    /** Fresh run token: one logical test run = one event tail. */
    public String newRunToken() {
        return events.newRunToken();
    }

    public void step(String token, String ref, String summary, String detail) {
        events.step(jobKind, ref, token, summary, detail);
    }

    /** Step carrying the sealed ciphertext sidecar of the masked trail. */
    public void step(String token, String ref, String summary, String detail, String sealed) {
        events.step(jobKind, ref, token, summary, detail, sealed);
    }

    /** Step carrying sealed material AND the unclipped twin of an over-budget trail. */
    public void step(String token, String ref, String summary, String detail,
                     String sealed, String fullDetail) {
        events.step(jobKind, ref, token, summary, detail, sealed, fullDetail);
    }

    public void error(String token, String ref, String summary, String detail) {
        events.error(jobKind, ref, token, summary, detail);
    }

    /** Transport-side failure line both doors tail identically. */
    public void transportError(String token, String ref, String target,
                               IOException transport, int timeoutMs) {
        String reason = transport instanceof HttpTimeoutException
                ? "超出等待预算 " + timeoutMs + "ms" : String.valueOf(transport.getMessage());
        error(token, ref, "请求未能送达目标：" + reason,
                target + "\n原因归类：网络/传输侧（目标地址不可达、连接被拒或超时）");
    }

    /**
 * Tails the full response frame (headers + budgeted body, secrets masked
 * and sealed) and returns the shared verdict for the same answer, so the
 */
    public Verdict responseStepAndVerdict(String token, String ref,
                                          ProbeExchangeRunner.ExchangeResult result) {
        ProbeExchangeRunner.TrailPiece frame = runner.renderResponseTrail(result);
        step(token, ref, "收到响应：HTTP " + result.statusCode() + " · " + result.elapsedMillis() + "ms",
                frame.text(), frame.sealed(), frame.full());
        return ProbeExchangeRunner.verdictOf(result.statusCode(), result.firstHeader("Location"));
    }
}
