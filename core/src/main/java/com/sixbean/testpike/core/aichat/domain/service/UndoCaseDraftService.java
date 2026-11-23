package com.sixbean.testpike.core.aichat.domain.service;

/**
 * One-key undo of the last accepted case write in a dialog session (AM batch
 * . Undo is a CARD, not a write: it files a fresh CASE_ROLLBACK
 * proposal aimed at the version that was live before the most recent applied
 * case write (create, edit or rollback alike), and the human confirmation
 * door stays the only thing that touches business rows. Refusals never leave
 * a half-inserted card behind.
 */
public interface UndoCaseDraftService {

    /**
     * Cut the undo card for the session's latest applied case write.
     *
     * @param sessionId the dialog session whose history is being undone
     * @return the ledger id of the new pending draft card
     */
    long undoLastAcceptedWrite(long sessionId);
}
