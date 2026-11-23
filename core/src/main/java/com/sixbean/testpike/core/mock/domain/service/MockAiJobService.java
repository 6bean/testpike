package com.sixbean.testpike.core.mock.domain.service;

import com.sixbean.testpike.core.mock.api.dto.MockAiJobCreateInput;
import com.sixbean.testpike.core.mock.api.dto.MockAiJobEstimateView;
import com.sixbean.testpike.core.mock.api.dto.MockAiJobDraftItemView;
import com.sixbean.testpike.core.mock.api.dto.MockAiJobInfo;
import com.sixbean.testpike.core.mock.api.dto.MockDraftArtifactGrantView;
import com.sixbean.testpike.core.mock.api.dto.MockStubInput;
import com.sixbean.testpike.core.mock.api.dto.MockStubInfo;

import java.util.List;

/**
 * AI mock-drafting job control plane . Owns the gate matrix
 * (master {@code ai.mock.enabled} AND one per-application-point sub-switch,
 * every point off by default), the creation-time budget pin, and the preview
 * ledger: AI output enters the rule source ONLY through a human-ticked
 * confirm — never directly, never by a consumer.
 * <p>This service does not publish MQ and never calls a model; the dispatch
 * rides on the controller publish point , execution on ai-service.
 */
public interface MockAiJobService {

    /**
     * Create a drafting job behind the gates. Refusals in plain words name the
     * exact config key to open (master closed, sub-switch closed, unknown or
     * not-this-period job type, blank prompt, ghost space); a refused call
     * leaves zero job rows and one audit line. An accepted call lands 'queued'
     * with the prompt body and the budget snapshot pinned at creation time.
     */
    MockAiJobInfo createJob(MockAiJobCreateInput input);

    /**
     * Estimate the cost of one job from the stored prompt and the per-point
     * output quota. Pure: writes nothing, calls no model — the operator sees
     * the price before any token burns.
     */
    MockAiJobEstimateView estimatePreview(long jobId);

    /** Read one job (ghost id refused in plain words, never null). */
    MockAiJobInfo get(long jobId);

    /** Jobs of one space, newest first (ghost space refused, not empty-listed). */
    List<MockAiJobInfo> listBySpace(long spaceId);

    /**
     * Land the human-ticked drafts into the single rule source and seal the
     * job. Only a 'preview' job confirms; an empty selection is refused (a
     * spent preview with nothing kept is an operator decision, not a default).
     * Every stub goes through MockStubService's save-chain validation with
     * provenance source=nl-ai + sourceRef=job:{id}; the job then seals
     * 'consumed' — a second confirm is refused.
     */
    List<MockStubInfo> confirm(long jobId, List<MockStubInput> selectedStubs);

    /**
     * Throw a live job away (queued/running/preview → cancelled). Terminal
     * states (consumed/failed/cancelled) are sealed history and refused.
     */
    MockAiJobInfo ignore(long jobId);

    /**
     * Consumer claim: queued → running only. A second claim, a preview hand-off
     * in progress, and every terminal state are refused — this transition is
     * what makes duplicate MQ delivery harmless (the loser sees a refusal).
     */
    MockAiJobInfo markRunning(long jobId);

    /**
     * Consumer artifact write-back: running → preview, storing the draft bundle
     * reference (inline JSON text or artifact URI, column-capped). Any other
     * source status is refused — a queued jump would let a stale delivery
     * re-preview a job that was never claimed.
     */
    MockAiJobInfo markPreview(long jobId, String resultPreviewRef);

    /**
     * Consumer failure: running → failed with a plain-word reason (clipped to
     * the column cap, never rejected — losing the failure signal is worse than
     * losing the tail of its wording). Terminal states are sealed history.
     */
    MockAiJobInfo markFailed(long jobId, String errorMessage);

    /**
 * Upload grant for the draft bundle a consumer produces prereq):
 * the model output far exceeds the preview-reference column cap, so the
 * consumer PUTs the raw bundle to object storage through this presigned
 * door and writes the returned objectKey back as the preview reference.
 * A ghost job is refused in plain words; the owning project of the space
 * drives the storage route (read/write same-route posture).
 */
    MockDraftArtifactGrantView artifactGrant(long jobId, String fileName);

    /**
 * Read the drafted items of a job back out of its stored bundle
 * read door of the write-only artifact grant): the confirmation drawer
 * ticks these into the confirm call, so the bundle's item fields are the
 * contract. Only preview/consumed jobs have items (consumed stays readable
 * for after-the-fact review); queued/running/failed/cancelled reads are
 * refused in plain words, and an artifact that vanished under a preview
 * row is refused honestly instead of answered with an empty list.
 */
    List<MockAiJobDraftItemView> previewDraftItems(long jobId);
}
