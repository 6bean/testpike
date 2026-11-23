package com.sixbean.testpike.core.mock.domain.service;

import com.sixbean.testpike.core.mock.api.dto.MockStubImportResult;
import com.sixbean.testpike.core.mock.domain.component.MockStubDraft;

import java.util.List;

/**
 * Contract-direct import service the entry/exit wiring of
 * the deterministic stub mapping): preview turns a stored contract version
 * row into save-ready drafts, import files them as rules of one mock space.
 */
public interface MockStubImportService {

    /**
     * Replay the endpoint snapshot of a spec version row through the
     * deterministic mapper (parser untouched, zero tokens). Unknown spec id
     * is refused in plain words.
     */
    List<MockStubDraft> previewDrafts(long specId);

    /**
     * File drafts under a space through the normal stub save chain — every
     * rule still passes MockRuleValidator at the single validation point (a
     * draft is a proposal, never a bypass). Drafts whose method+path the space
     * already carries are skipped, not duplicated, so re-importing the same
     * contract is idempotent. Any illegal draft rolls the whole import back
     * (all-or-nothing — a half-imported contract would misreport coverage).
     */
    MockStubImportResult importDrafts(long spaceId, List<MockStubDraft> drafts);
}
