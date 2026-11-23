package com.sixbean.testpike.core.aichat.domain.service;

import com.sixbean.testpike.core.aichat.api.dto.EvolutionProposalView;

/**
 * Proposal side of the self-evolution loop (AF-i phase 2b-ii): turns the
 * read-only observation rollup into draft cards of the three improvement
 * families the loop is allowed to suggest — prompt-template revisions,
 * new tool definitions and grounding-document revisions.
 * <p>Red lines this door holds by construction:
 * <ul>
 *   <li>manual trigger only — the run is fired by an admin action, never by
 *       a scheduler, and a produced card NEVER lands or publishes without a
 *       human walking the shared draft-confirmation door;</li>
 *   <li>assembly-time negative gate — every candidate surface name runs
 *       through {@code EvolutionNegativeGate.requireAllowed} before a card
 *       is built, so a proposal touching a forbidden class (own code, gates
 *       and keys, confirmation signing, auto-publish) is refused here and
 *       never reaches the confirmation chain at all;</li>
 *   <li>single ledger — cards land as pending rows of {@code
 *       tp_ai_chat_draft} (object_type EVOLUTION_PROPOSAL) with the landing
 *       body inside the payload; no parallel proposal table exists.</li>
 * </ul>
 */
public interface EvolutionProposalService {

    /**
 * Run one proposal generation for a month window.
 *
 * @param month exact yyyy-MM window key (fail-closed otherwise)
 * @param projectId project segment for dialog signals; null = platform-wide
 * @param operatorId the logged-in human who triggered the run (owns the
 * evolution session and later confirms its cards)
 * @return the generated pending cards (empty list when the window carried
 * no actionable complaint signal)
 */
    EvolutionProposalView generate(String month, Long projectId, long operatorId);

    /**
     * One-click rollback of a LANDED prompt-family proposal card.
     * <p>The version chain is never invented here: rollback consumes the
     * prompt domain's existing precedent — the version that the proposal
     * replaced is copied forward as a new family version whose {@code
     * rollback_to} names the copied source (same semantics the human
     * template page uses, so both faces of the chain stay single-sourced).
     * <p>Guards: the card exists and belongs to the caller's session
     * (ownership door, same sentence family as apply); it is an
     * EVOLUTION_PROPOSAL that was APPLIED (pending cards wrote nothing,
     * voided cards are dead); it was never rolled back before (the draft
     * row carries the once-only mark); the family has a version chain
     * (tool/grounding families honestly refuse). The audit row pins the
     * operator, the triggering run session and both version numbers.
     *
     * @param draftId    the applied EVOLUTION_PROPOSAL draft card
     * @param operatorId the logged-in human requesting the rollback
     * @return the newly copied-forward template version
     */
    com.sixbean.testpike.core.prompt.api.dto.PromptTemplateItem rollback(
            long draftId, long operatorId);
}
