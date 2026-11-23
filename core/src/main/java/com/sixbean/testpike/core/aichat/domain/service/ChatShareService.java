package com.sixbean.testpike.core.aichat.domain.service;

import com.sixbean.testpike.core.aichat.api.dto.ChatShareInfo;
import com.sixbean.testpike.core.aichat.api.dto.ChatShareSnapshotView;

import java.util.List;

/**
 * Read-only dialog snapshot shares .
 * <p>The door a share answers through is public: whoever holds the token reads
 * the frozen copy without an account. That is exactly why the snapshot is
 * copied, not viewed — {@link #create} runs the export pipeline (permission
 * pruning through the owner check, masking, unsealed turns dropped) once and
 * stores its output, so a link handed out yesterday cannot leak what the
 * session gained today, and expiry or revocation retire the copy on its own.
 */
public interface ChatShareService {

    /**
     * Freeze one owned session into a share snapshot.
     *
     * @param userId owner pinned from the login context — a stranger gets the
     *               named ownership refusal before anything is rendered
     * @param ttlHours requested lifetime, clamped into the 1h..7d window
     *                 (a share is a temporary hand-off, never a permanent door)
     */
    ChatShareInfo create(long userId, long sessionId, int ttlHours);

    /**
     * Serve the frozen copy for a public link touch. Garbage tokens are
     * refused by shape (400); unknown, expired and revoked links all answer
     * the same 404 sentence so the door never reveals which tokens once
     * existed. An expired row is deleted on this touch (self-destruct).
     */
    ChatShareSnapshotView openByToken(String shareToken);

    /** Owner-side kill switch: flips revoked, idempotent, never deletes the audit row. */
    ChatShareInfo revoke(long userId, long shareId);

    /**
     * The owner's own share list for one session, newest first. Rows past
     * their expiry are excluded here (the lazy self-destruct still finishes
     * the job on a public touch; the list face simply never shows a dead
     * link as if it were alive). Revoked rows stay visible so the owner can
     * see what they killed.
     */
    List<ChatShareInfo> listForOwner(long userId, long sessionId);
}
