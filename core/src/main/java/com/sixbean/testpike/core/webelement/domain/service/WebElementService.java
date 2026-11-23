package com.sixbean.testpike.core.webelement.domain.service;

import com.sixbean.testpike.core.webelement.api.dto.WebElementAnchorInput;
import com.sixbean.testpike.core.webelement.api.dto.WebElementInput;
import com.sixbean.testpike.core.webelement.api.dto.WebElementPageView;
import com.sixbean.testpike.core.webelement.api.dto.WebElementView;

import java.util.List;

/**
 * Web element ledger doors (WE batch S0). One row = one page element with an
 * ordered set of locator anchors; a redesign repairs one ledger row instead of
 * every referencing case. The service enforces the anchor value domain, the
 * page-key normalization (shared rule) and the lifecycle transition table,
 * and stamps the optimistic lock on every write.
 */
public interface WebElementService {

    /** Create a candidate row; duplicate (project, page, name) is refused. */
    WebElementView create(WebElementInput input);

    /** Single row read; missing rows refuse with the shared not-found code. */
    WebElementView findById(long id);

    /** Project segment; pageKey optional filter on the folded page key. */
    List<WebElementView> listByProject(long projectId, String pageKey);

    /** Page roll-up with lifecycle counts, ordered by page key. */
    List<WebElementPageView> pagesOfProject(long projectId);

    /** Replace the ordered anchor set; consumes the caller's revision token. */
    WebElementView updateAnchors(long id, List<WebElementAnchorInput> anchors, int expectedRevision);

    /**
     * Replace the per-viewport-class anchor sets (WE batch S4 design 8.2);
     * consumes the caller's revision token. An empty list clears the tiers
     * (single-viewport asset); tiers never change the row's state.
     */
    WebElementView updateVariants(long id,
                                  List<com.sixbean.testpike.core.webelement.api.dto.WebElementVariantInput> variants,
                                  int expectedRevision);

    /** Lifecycle move along the transition table; consumes the revision token. */
    WebElementView changeState(long id, int targetState, int expectedRevision);

    /**
 * Rename one element . Names address dispatch
 * references, so a mis-named row was previously unfixable. Consumes the
 * caller's revision token, keeps the row on its page, and the
 * (project, page, name) unique gate stays the single arbiter of same-page
 * clashes; replaying the current name is a no-op (no revision churn).
 */
    WebElementView rename(long id, String newName, int expectedRevision);

    /**
     * Dispatch snapshot read (WE batch S1): every referenced name resolves in
     * ONE batched query. Confirmed rows always qualify; candidate rows only
     * when the draft trial run opted in via allowCandidate; stale/retired rows
     * never travel. Ordered by page key for a stable grouping input.
     */
    List<WebElementView> findForDispatch(long projectId, java.util.Collection<String> names,
                                         boolean allowCandidate);

    /**
 * Prompt-summary read (WE batch S5, piece A): the generation prompts
 * ask for the CONFIRMED elements of one project (optionally one folded
 * page key), strongest anchors first, capped rows — never the whole table.
 * Ordering is confidence DESC with an id ASC tie-break so the same ledger
 * renders byte-stable across ring rounds (provider prompt-cache economy);
 * the limit clamps into 1..500 server-side whatever the caller sends.
 */
    List<WebElementView> promptSummary(long projectId, String pageKey, int limit);
}
