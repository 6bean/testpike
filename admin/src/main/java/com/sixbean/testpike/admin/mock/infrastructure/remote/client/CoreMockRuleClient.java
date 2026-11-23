package com.sixbean.testpike.admin.mock.infrastructure.remote.client;

import com.sixbean.testpike.admin.mock.api.dto.MockAiJobCreateRequest;
import com.sixbean.testpike.admin.mock.api.dto.MockAiJobDraftItemView;
import com.sixbean.testpike.admin.mock.api.dto.MockAiJobEstimateView;
import com.sixbean.testpike.admin.mock.api.dto.MockAiJobView;
import com.sixbean.testpike.admin.mock.api.dto.MockHitPageView;
import com.sixbean.testpike.admin.mock.api.dto.MockPlaybackResultView;
import com.sixbean.testpike.admin.mock.api.dto.MockRecordingPageView;
import com.sixbean.testpike.admin.mock.api.dto.MockRecordingView;
import com.sixbean.testpike.admin.mock.api.dto.MockServingEndpointPageView;
import com.sixbean.testpike.admin.mock.api.dto.MockScenarioInput;
import com.sixbean.testpike.admin.mock.api.dto.MockScenarioPageView;
import com.sixbean.testpike.admin.mock.api.dto.MockScenarioView;
import com.sixbean.testpike.admin.mock.api.dto.MockSpaceInput;
import com.sixbean.testpike.admin.mock.api.dto.MockSpaceView;
import com.sixbean.testpike.admin.mock.api.dto.MockStubDraftView;
import com.sixbean.testpike.admin.mock.api.dto.MockStubImportResultView;
import com.sixbean.testpike.admin.mock.api.dto.MockStubInput;
import com.sixbean.testpike.admin.mock.api.dto.MockStubPageView;
import com.sixbean.testpike.admin.mock.api.dto.MockStubView;

import java.util.List;

/**
 * core mock rule-plane client interface (same posture as
 * CoreReleaseCandidateClient — thin pass-through, zero reprocessing; all
 * save-chain gates and ordering semantics live in core).
 */
public interface CoreMockRuleClient {

    /** Create a space under a project; returns the new row id. */
    long createSpace(long projectId, MockSpaceInput input);

    /** Spaces of one project in routing order (priority asc, id asc). */
    List<MockSpaceView> listSpaces(long projectId);

    /**
     * Lifecycle transition (draft→publish, publish→retire). The edge set,
     * optimistic lock and publish-time production-env re-check are core's
     * single point; a stale expectedVersion comes back as 40901 with the
     * human "refresh and retry" wording, forwarded untouched.
     */
    MockSpaceView transitionSpace(long spaceId, int targetStatus, int expectedVersion);

    /**
 * Save the space's miss ladder . Rung-name legality and the
 * optimistic-lock verdict are the core policy door's single point; a stale
 * expectedVersion comes back as 40901 with the human "refresh and retry"
 * wording, forwarded untouched.
 */
    MockSpaceView updateMissPolicy(long spaceId, String missPolicy, int expectedVersion);

    /** Create a stub rule under a space; returns the new row id. */
    long createStub(long spaceId, MockStubInput input);

    /**
     * Edit one rule in place (same core save gates as create). The echoed
     * expectedVersion inside the input guards lost updates; a stale echo
     * returns the plain-word version conflict forwarded untouched.
     */
    MockStubView updateStub(long stubId, MockStubInput input);

    /** Remove one rule (the owning space pin moves; traffic stops serving it). */
    void deleteStub(long stubId);

    /**
     * Matched serves of one rule over the last {@code days} days — the impact
     * number the confirmation door shows before editing/deleting a live rule.
     */
    long recentStubHits(long stubId, int days);

    /**
     * One paged window of the project-wide serving inventory (every mock
     * endpoint across the project's spaces, routing order + 7-day hit count).
     * Clamp, ordering and the truthful total are core semantics forwarded
     * verbatim.
     */
    MockServingEndpointPageView pageServingEndpoints(long projectId, int page, int pageSize,
                                                     Integer status, String method, String keyword);

    /**
     * Sorting form: forwards the whitelisted sort key/direction so the ORDER
     * BY happens in core SQL (the page counter and total stay meaningful).
     * Null/blank sort params keep the historical routing order.
     */
    MockServingEndpointPageView pageServingEndpoints(long projectId, int page, int pageSize,
                                                     Integer status, String method, String keyword,
                                                     String sortBy, String sortDir);

    /**
     * Contract-direct preview: replays the stored endpoint snapshot of a spec
     * version row through core's deterministic mapper (zero tokens). Drafts are
     * save-ready proposals — nothing is persisted here; every gate still runs at
     * import time through the normal stub save chain.
     */
    List<MockStubDraftView> previewStubDrafts(long specId);

    /**
     * Contract-direct import: posts the wizard's chosen drafts into core, which
     * files each through the normal stub save chain. Drafts already covered by
     * the space for the same method+path come back as skipped — re-import of an
     * unchanged contract is idempotent (no second validation here, red line 6).
     */
    MockStubImportResultView importStubs(long spaceId, List<MockStubDraftView> drafts);

    /** Rules of one space in evaluation order (priority asc, id asc). */
    List<MockStubView> listStubs(long spaceId);

    /**
 * One paged window of the space's rules in evaluation order
 * — display-scale iron law on the admin list door). Clamp, ghost-space
 * refusal and the truthful total are core semantics forwarded verbatim.
 */
    MockStubPageView pageStubs(long spaceId, int page, int pageSize);

    /**
 * File a stateful scenario declaration under a space . The machine
 * JSON is forwarded verbatim — name shape, declaration validity and the
 * duplicate-name refusal are core's save-chain single point.
 */
    long createScenario(long spaceId, MockScenarioInput input);

    /** Scenario declarations of one space in name order (never re-sorted here). */
    List<MockScenarioView> listScenarios(long spaceId);

    /**
 * One paged window of the space's scenarios in name order
 * feeds the new /mock/scenarios list page; core owns clamp + total).
 */
    MockScenarioPageView pageScenarios(long spaceId, int page, int pageSize);

    /**
 * Sorting form : forwards the whitelisted sort key and
 * direction so core applies the ORDER BY in SQL; null/blank keeps the
 * historical name order.
 */
    MockScenarioPageView pageScenarios(long spaceId, int page, int pageSize,
                                       String sortBy, String sortDir);

    /**
     * One hit-ledger inspector page for a space (newest serve-time first).
     * matched=null asks for both spectra; core refuses ghost space ids.
     */
    MockHitPageView listHits(long spaceId, Boolean matched, int page, int pageSize);

    /**
 * Sorting form : forwards the whitelisted sort key and
 * direction so core applies the ORDER BY in SQL; null/blank keeps the
 * historical newest-first timeline.
 */
    MockHitPageView listHits(long spaceId, Boolean matched, int page, int pageSize,
                             String sortBy, String sortDir);

    /**
 * Open a cassette box on a published space . The whole
 * gate ladder — published-state, target shape, allowlist fail-closed,
 * host-by-name refusal, one-open-box — is core's save-chain single point;
 * the human-worded ServiceException code comes back through the shared
 * invoker untouched (no bridge-side re-validation, red line 6).
 */
    long startRecording(long spaceId, String targetBaseUrl);

    /** Seal an open box (recording→stopped with ended_at; double-seal refused by core). */
    MockRecordingView stopRecording(long recordingId);

    /** Cassette boxes of one space, newest first (core refuses ghost spaces). */
    List<MockRecordingView> listRecordings(long spaceId);

    /**
 * One paged window of the space's boxes the recording
 * ledger page consumes this door; core owns clamp + total + whitelist
 * order). Null/blank sort keeps the historical newest-first order.
 */
    MockRecordingPageView pageRecordings(long spaceId, int page, int pageSize,
                                         String sortBy, String sortDir);

    /**
 * Replay preview of one box : recorded frames mapped
 * into save-ready drafts under strict|fuzzy — nothing persisted, the
 * drawer confirms before anything files.
 */
    List<MockStubDraftView> previewPlayback(long recordingId, String mode);

    /**
     * File a box's replay plan through core's single import channel (normal
     * save chain + idempotence + promoted_count stamp all stay core-side; the
     * bridge passes spaceId/recordingId/mode untouched, red line 6).
     */
    MockPlaybackResultView playback(long spaceId, long recordingId, String mode);

    /**
 * Promote one answered hit-ledger row into a stub rule
 * the inspector's "make this request a stub"). Mapping, refusal wording,
 * and idempotent skip all live core-side behind the single import channel;
 * the bridge carries spaceId/hitId and the result view untouched.
 */
    MockStubImportResultView promoteHit(long spaceId, long hitId);

    /**
 * Create an AI drafting job . Every gate — master switch ANDed
 * with the point sub-switch, prompt shape, job-type domain — is the core
 * service's single point; the bridge forwards the plain-word refusal
 * untouched and files no validation of its own (red line 6).
 */
    MockAiJobView createAiJob(MockAiJobCreateRequest request);

    /** Pre-run cost estimate of a stored job (core arithmetic; nothing persists). */
    MockAiJobEstimateView estimateAiJob(long jobId);

    /** One AI drafting job (the task card's detail door). */
    MockAiJobView getAiJob(long jobId);

    /** Drafting jobs of one space, newest first (core refuses ghost spaces). */
    List<MockAiJobView> listAiJobs(long spaceId);

    /**
     * Human-ticked drafts into the rule source — the only AI ingress. The
     * preview-only state gate, per-stub save-chain validation and the forced
     * source=nl-ai provenance all stay core-side; the bridge forwards the
     * ticked list and lands nothing itself.
     */
    List<MockStubView> confirmAiJob(long jobId, List<MockStubInput> selectedStubs);

    /** Throw a live job away (terminal-state refusal is core's sealed-history rule). */
    MockAiJobView ignoreAiJob(long jobId);

    /**
 * Drafted items of a job read back from its stored bundle (the preview
 * drawer's data door, . The state spectrum and the
 * honest refusals (no preview yet / artifact vanished / bundle broken)
 * are core's single point — the bridge forwards wording untouched.
 */
    List<MockAiJobDraftItemView> listAiJobDrafts(long jobId);
}
