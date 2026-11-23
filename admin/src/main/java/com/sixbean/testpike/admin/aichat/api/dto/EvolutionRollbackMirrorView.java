package com.sixbean.testpike.admin.aichat.api.dto;

/**
 * Admin-side mirror of the prompt version produced by rolling back one landed
 * self-evolution proposal (core AF-i phase 3). Field names match the core
 * version row one-to-one — the bridge crosses HTTP by name, so a rename on
 * either side silently blanks a summary line the human just clicked.
 * <p>The template body is deliberately NOT mirrored: the rollback receipt
 * tells the operator which family moved to which version, never re-displays
 * the prompt text, so the full column stays on the core lane.
 *
 * @param id         the new tp_prompt_template row copied forward
 * @param name       template family name
 * @param domain     business domain of the family
 * @param version    new family version number (one step forward)
 * @param status     1 grey / 2 full / 3 history (rollback lands grey, the
 *                   same posture the human template page uses)
 * @param createdBy  the logged-in human who clicked rollback
 * @param rollbackTo source row id the content was copied from — the proof the
 *                   version chain was reused, not invented
 * @param createdAt  'yyyy-MM-dd HH:mm:ss'
 */
public record EvolutionRollbackMirrorView(Long id,
                                          String name,
                                          String domain,
                                          Integer version,
                                          Integer status,
                                          Long createdBy,
                                          Long rollbackTo,
                                          String createdAt) {
}
