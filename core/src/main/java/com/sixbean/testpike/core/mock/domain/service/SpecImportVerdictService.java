package com.sixbean.testpike.core.mock.domain.service;

import com.sixbean.testpike.core.mock.api.dto.MockStubDraftAdvice;
import com.sixbean.testpike.core.mock.api.dto.SpecImportVerdict;
import com.sixbean.testpike.core.mock.domain.component.MockStubDraft;

import java.util.List;

/**
 * Single point of the contract-bulk import pre-adjudication (MJ P3): every
 * draft row gets a structured advice — recommend / avoid, one-sentence reason,
 * conflict handling, impact reading, realism and confidence — stored so the
 * preview survives paging and can later be refined by an AI backfill round.
 * <p>Deterministic by design: the first frame never waits for an LLM. The AI
 * layer (when wired) only refines stored rows; a missing or late AI answer
 * degrades to these rule verdicts with an honest label, never a blank screen.
 */
public interface SpecImportVerdictService {

    /**
     * Compute and store the deterministic advice set for one preview round.
     * Re-running over the same (spec, space) updates every row in place —
     * the table never accumulates twin advice for one endpoint.
     */
    List<SpecImportVerdict> judge(long specId, long spaceId, List<MockStubDraft> drafts);

    /** Stored advice rows for one preview, ordered by endpoint key. */
    List<SpecImportVerdict> listVerdicts(long specId, long spaceId);

    /**
     * Preview join of the deterministic floor with its advice state: every
     * draft row comes back with verdict, honest waiting label and the wizard
     * default selection. First frame never waits for an LLM — rows read
     * pending inside the backfill deadline, degraded past it, refined once
     * the AI round lands.
     */
    List<MockStubDraftAdvice> advise(long specId, long spaceId, List<MockStubDraft> drafts);

    /**
     * Single intake port of the asynchronous AI backfill round (adjudication
     * D6: the spec-enrich job family is the only writer that calls this, no
     * second runner). Updates stored rows in place to origin=ai; keys the
     * deterministic floor never judged and unusable verdicts are skipped —
     * intake never invents or twins rows.
     */
    void applyAiRefinement(long specId, long spaceId, List<SpecImportVerdict> refined);
}
