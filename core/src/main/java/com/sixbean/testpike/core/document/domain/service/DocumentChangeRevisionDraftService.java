package com.sixbean.testpike.core.document.domain.service;

import com.sixbean.testpike.core.document.api.dto.RevisionDraftPregen;

/**
 * Lazy pre-generation of requirement revision-draft cards for one pending
 * document change analysis .
 * <p>When a reviewer opens the disposition view, every change item that names
 * a live, same-project requirement with new chapter content gets exactly one
 * anchored dialog run through {@code ChatRunService.openRun} — the ONE single
 * write source of runs (no parallel door, no direct frame writes). The AI
 * answers with REQUIREMENT_WRITE draft cards that keep the existing one-click
 * confirmation door (red line 10c: pre-generation NEVER writes a requirement
 * version, it only prepares cards a human confirms).
 * <p>Idempotent by a double anchor, so re-opening the view never re-spends a
 * run: a pending draft already on the ledger for that requirement, or the
 * sealed user echo of an earlier dispatch (which also covers the in-flight
 * window before the AI card lands). Sessions are find-or-created per
 * (operator, project, source 3, analysisId).
 */
public interface DocumentChangeRevisionDraftService {

    /**
     * Pre-generate revision-draft runs for one analysis.
     *
     * @param operatorId the opening reviewer (owns the session, horizontal
     *                   red line AF-g applies to every run)
     * @param analysisId the analysis snapshot (non-pending analyses answer
     *                   with an all-zero receipt and never touch the ledger)
     * @return the receipt ledger of this pass (see {@link RevisionDraftPregen})
     */
    RevisionDraftPregen pregenDrafts(long operatorId, long analysisId);
}
