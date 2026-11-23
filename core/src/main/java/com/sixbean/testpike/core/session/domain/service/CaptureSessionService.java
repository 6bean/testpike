package com.sixbean.testpike.core.session.domain.service;

import com.sixbean.testpike.core.session.api.dto.CaptureCandidateMetadata;
import com.sixbean.testpike.core.session.api.dto.CaptureCommitValues;
import com.sixbean.testpike.core.session.api.dto.CaptureSessionClaim;
import com.sixbean.testpike.core.session.api.dto.CaptureSessionInfo;
import com.sixbean.testpike.core.session.api.dto.CaptureSessionInput;
import com.sixbean.testpike.core.session.api.dto.CaptureSessionReport;
import com.sixbean.testpike.core.session.api.dto.CaptureSlotStageInput;

import java.util.List;

/**
 * Interactive capture-session lifecycle (AV1 LOGIN_CAPTURE main path).
 *
 * <p>Everything lives on the transient session channel (Redis + TTL, design
 * secondary-path shell switch, and the session disappears on every terminal
 * exit — commit, timeout or explicit abandon. Secret values never transit this
 * service; they only enter the vault through {@code LoginStateService.register}
 * when the commit round runs (registered with the capture-source lineage).
 */
public interface CaptureSessionService {

    /**
     * Open one capture session; returns the session id.
     * Refuses before creating anything when the scope whitelist is empty, the
     * anchor is malformed, or {@code mode=host-window} while the secondary-path
     * shell switch is off (honest two-element wording, R32).
     *
     * <p>The opened session is queued for hand-off: an executor picks it up with
     * {@link #claim(long)} and answers with {@link #report(String, CaptureSessionReport)}.
     */
    String open(long projectId, CaptureSessionInput input);

    /**
     * Executor hand-off: pop one queued session and hand its capture parameters
     * (including the debug endpoint, which never leaves this transient channel)
     * to the calling executor. A session is claimable once per round; a failed
     * or empty round is re-queued so the human can finish logging in.
     *
     * @return the claim projection, or null when nothing is waiting (honest absence)
     */
    CaptureSessionClaim claim(long executorId);

    /**
     * Executor answer for one round. Candidate rows outside the session origin
     * whitelist are dropped server-side as well (red line 2 — the executor's
     * filter is not trusted blindly), and only the four metadata fields are kept.
     *
     * @return number of candidate rows stored, or -1 when the session is gone
     */
    long report(String sessionId, CaptureSessionReport report);

    /** Session metadata for the wizard; absent/expired = honest expiry wording. */
    CaptureSessionInfo find(String sessionId);

    /**
 * Metadata only — the four fields written back by the executor; there is
 * no value slot in this shape (red line 3). Absent/expired session raises
 * the same honest expiry wording as {@link #find(String)}.
 */
    List<CaptureCandidateMetadata> candidates(String sessionId);

    /**
 * checked subset on the session and re-queues it so the next executor
 * claim carries {@code commitSelection} — the reverse signal rides the
 * same hand-off queue, no second channel. Names outside the stored
 * candidate list are dropped; an all-dropped selection is refused (the
 * checked set is an explicit authorization act, an empty one is meaningless).
 *
 * @return number of checked names accepted for the commit round
 */
    int commit(String sessionId, List<String> selectedNames);

    /**
     * Executor answer for the commit round. Values transit memory straight
     * into {@code LoginStateService.register} (encrypted vault, capture
     * lineage, D9 same-name renewal) — nothing here is persisted on the
     * session channel, and every terminal path deletes the session keys plus
     * its queue entry (R57). An honest failure reason (browser session gone)
     * terminally invalidates the session so the wizard sees the re-open
     * wording instead of silence.
     *
     * @return number of entries registered, 0 when nothing was accepted (the
     *         session stays live for a retry inside the window), -1 when the
     *         session is gone or the round failed
     */
    long commitValues(String sessionId, CaptureCommitValues values);

    /** Explicit abandon: deletes the session keys and its hand-off queue entry (idempotent). */
    void abandon(String sessionId);

    /**
 * Screenshot staging channel : park the freshly
 * parsed rows of one chat screenshot (values included) in the same
 * TTL-bound transient store the wizard sessions live in, under a new
 * staging token, and return that token. Nothing value-bearing echoes:
 * the chat side gets the token, the draft strategy re-reads and deletes
 * the slot at landing time (piece E red line — values never reach
 * PostgreSQL, logs or the draft payload).
 *
 * @return the staging token (the load key of the transient slot)
 */
    String stageScreenshotEntries(CaptureSlotStageInput input);

    /**
 * Name-domain probe of one screenshot staging slot : the
 * draft emitter aligns every card row to these names before minting, so
 * what the human ticks is byte-identical with what the landing door's
 * fail-closed per-row check re-reads. Names only — values never transit
 * this door (piece E red line); an absent or expired slot answers an
 * empty list (the caller refuses its card honestly upstream).
 */
    List<String> stagedNames(String token);
}
