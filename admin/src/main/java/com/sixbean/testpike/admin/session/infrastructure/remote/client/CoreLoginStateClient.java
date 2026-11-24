package com.sixbean.testpike.admin.session.infrastructure.remote.client;

import com.sixbean.testpike.admin.session.api.dto.LoginStateKeepAliveRequest;
import com.sixbean.testpike.admin.session.api.dto.LoginStateProbeRunRequest;
import com.sixbean.testpike.admin.session.api.dto.LoginStateRegisterRequest;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.project.api.dto.ProbeOutcomeView;
import com.sixbean.testpike.admin.ai.api.dto.AiTaskEventView;
import com.sixbean.testpike.admin.session.api.dto.LoginStateView;

import java.util.List;

/**
 * core login-state ledger client interface (Admin→core
 * /internal/session-states pass-through, RestCoreCredentialClient idiom).
 *
 * <p>The bridge deliberately carries no reveal door: plaintext resolution
 * happens server-side inside core at dispatch/consumption time (never-echo
 * red line), and verification write-back is a server-side signal the UI must
 * not be able to forge.
 */
public interface CoreLoginStateClient {

    /** Register or renew one state (D9 same-name update, never append). */
    long register(long projectId, LoginStateRegisterRequest request);

    /** Edit one ledger row in place by id (blank secret keeps the vault value). */
    void update(long id, LoginStateRegisterRequest request);

    /** One ledger row (missing = core honest 404 wording propagates). */
    LoginStateView findState(long id);

    /** Project segment (enabledOnly=TRUE keeps usable rows; null = all). */
    List<LoginStateView> listStates(long projectId, Boolean enabledOnly);

    /**
 * Paged ledger segment for the management face (keyword on state name or
 * account label, status 1/0/null, sortBy restricted to ledger columns).
 */
    PageView<LoginStateView> listStatesPage(long projectId, String keyword, Integer status,
                                            String sortBy, String sortDir, int page, int size);

    /** D9 resolution: exact by stateName, or role-scope with fail-closed ambiguity. */
    LoginStateView resolveState(long projectId, String stateName, String roleScope);

    /** Enable/disable (disable takes effect from the next session). */
    void setEnabled(long id, boolean enabled);

    /**
     * Keep-alive switch for one row: enabling runs the synchronous double
     * pre-check server-side in core; refusals surface with the core wording
     * unchanged. Carries the optional probe url and per-row interval minutes.
     */
    void setKeepAlive(long id, LoginStateKeepAliveRequest request);

    /** Remove the ledger row (the vault row remains for audit). */
    void delete(long id);

    /**
     * "Test now": replay the row's session against one target (blank url =
     * first recorded scope origin). Runs synchronously server-side; the
     * exchange lands in the process-event tail under the returned token.
     * The optional run payload carries the request shape (GET/POST/PUT plus
     * a body template with {{CRED:key}} vault references) — transient, never
     * stored on the ledger.
     */
    ProbeOutcomeView probeState(long id, String url, LoginStateProbeRunRequest run);

    /** Legacy two-argument shape: plain GET replay, no run payload. */
    default ProbeOutcomeView probeState(long id, String url) {
        return probeState(id, url, null);
    }

    /** Event window after cursor for one probe run. */
    java.util.List<AiTaskEventView> probeEvents(long id, String runToken, long cursor);

    /** Run history of one probe face (revisit entry list, newest run first). */
    java.util.List<com.sixbean.testpike.admin.ai.api.dto.AiTaskRunView> probeRuns(long id, Integer limit);

    /** Keep-alive transition trail after cursor for one ledger row (R67 revisit door). */
    java.util.List<AiTaskEventView> keepaliveEvents(long id, String runToken, long cursor);

    /** Keep-alive run history of one ledger row (transition frames only). */
    java.util.List<com.sixbean.testpike.admin.ai.api.dto.AiTaskRunView> keepaliveRuns(long id, Integer limit);

    /** Gated reveal of one sealed header value of one probe trail row. */
    com.sixbean.testpike.admin.project.api.dto.TrailRevealView revealProbeTrailField(
            long id, long eventId, String field);

    /** Paged full-text window of one probe trail row. */
    com.sixbean.testpike.admin.project.api.dto.TrailFullTextView probeTrailFullText(
            long id, long eventId, int from, Integer limit);
}
