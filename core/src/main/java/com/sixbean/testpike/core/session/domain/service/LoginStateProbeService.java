package com.sixbean.testpike.core.session.domain.service;

/**
 * "Test now" door for the login-state ledger: replays the stored session
 * snapshot against one target origin with the real outbound HTTP client and
 * records every step (address, masked request, response, verdict) into the
 * shared process-event ledger so the UI can tail it.
 */
public interface LoginStateProbeService {

    /** Verdict codes echoed to the caller (the full trail lives in events). */
    enum Verdict {
        /** 2xx - the session still passes at the target. */
        VALID,
        /** 401/403 or a redirect onto a login page - the session is stale. */
        EXPIRED,
        /** The target answered, but the status does not decide the question. */
        UNKNOWN,
        /** No request could run (snapshot, address or transport problem). */
        FAILED;

        /** Shared wording so both doors read the same verdict language. */
        public String human() {
            return switch (this) {
                case VALID -> "目标接受（2xx）";
                case EXPIRED -> "目标拒绝：会话或凭据疑似失效";
                case UNKNOWN -> "结果需人工确认";
                case FAILED -> "未能完成探测";
            };
        }
    }

    /**
     * Runs one probe synchronously and returns the tail handle.
     *
     * @param id           ledger row
     * @param targetUrl    optional explicit probe address; blank = the first
     *                     recorded scope origin of the row
     * @return run token (event cursor key) plus the verdict
     */
    ProbeResult probe(long id, String targetUrl);

    /**
     * Run with an explicit request shape (11-19 user command: POST + body
     * straight at the target endpoint, mirroring the vault test door's
     * method/body surface so one interaction grammar serves both doors).
     *
     * @param method       GET (default) / POST / PUT; anything else is
     *                     refused by name
     * @param bodyTemplate request-body template; {@code {{CRED:key}}} tokens
     *                     resolve against this project's vault at send time
     *                     (never stored, never echoed — the trail shows the
     *                     template source with placeholders intact); a
     *                     non-blank body under GET is dropped with a hint
     */
    default ProbeResult probe(long id, String targetUrl, String method, String bodyTemplate) {
        return probe(id, targetUrl);
    }

    /** Tail handle for the drawer plus the one-word verdict. */
    record ProbeResult(String runToken, Verdict verdict) {
    }
}
