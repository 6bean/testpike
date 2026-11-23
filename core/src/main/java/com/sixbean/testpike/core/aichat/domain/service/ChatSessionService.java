package com.sixbean.testpike.core.aichat.domain.service;

import com.sixbean.testpike.common.aichat.ChatContextKeys;
import com.sixbean.testpike.core.aichat.api.dto.ChatSessionInfo;
import com.sixbean.testpike.core.aichat.api.dto.ChatSessionPageView;

import java.util.List;
import java.util.Set;

/**
 * Dialog session shell service . Ownership lives here: a session
 * belongs to exactly one user, and every frame/message path re-checks it — the
 * horizontal-access red line (AF-g) applies to conversations the same way as to
 * test assets.
 */
public interface ChatSessionService {

    /**
 * Envelope keys of the session context jsonb — navigation pointers written
 * by every open door (route + the session's own projectId). They orient the
 * deep link; they are NOT pinned facts, so the waterline's fact count
 * subtracts them ( closure fix: a fresh session must not read as
 * "2 pinned facts on site").
 */
    Set<String> CONTEXT_ENVELOPE_KEYS = ChatContextKeys.ENVELOPE_KEYS;

    /**
 * Open a session for {@code userId} inside {@code projectId}.
 *
 * @param title hand title (stored stripped); blank/null lands the empty
 * placeholder — the FIRST user question then names the
 * session server-side
 * @param source entry surface: 1 dock / 2 standalone page / 3 badge deep-link
 * @param contextJson whitelist context object (route/projectId/objectId/executionId
 * class); a JSON array or garbage is refused — bulk user data
 * never enters a prompt through this field
 */
    ChatSessionInfo create(long projectId, long userId, String title, int source, String contextJson);

    /** Session view; refuses with a named reason when the session is not {@code userId}'s. */
    ChatSessionInfo requireOwned(long sessionId, long userId);

    /** Session view without an ownership check — for the frame ledger's internal cursor work only. */
    ChatSessionInfo findById(long sessionId);

    /**
 * Rename one owned session . The title is stored stripped; blank or
 * over-length titles are refused fail-closed, a stranger gets the named
 * ownership refusal. Renaming does not bump the activity cursor — history
 * order follows conversation traffic, not bookkeeping edits.
 */
    ChatSessionInfo rename(long sessionId, long userId, String title);

    /**
 * Archive one owned session : status flips to 1, history stays —
 * archive never deletes. Idempotent: re-archiving an archived session
 * returns the same view instead of an error.
 */
    ChatSessionInfo archive(long sessionId, long userId);

    /**
 * Name a title-less session from its first user question .
 * Only the empty placeholder is replaced, so a hand-typed title always
 * wins and later turns never re-title the thread. Invoked by the frame
 * ledger when a user turn seals — the one door every question passes.
 */
    void applyAutoTitle(long sessionId, String firstQuestion);

    /**
 * Paged session list for the history panel . Rows are owner-scoped by
 * {@code userId} (a foreign conversation is unreachable through this door —
 * the same horizontal posture as every other read path), and sessions with
 * zero messages never enter the ledger (an empty shell is noise the user
 * cannot recognise). Newest activity first; {@code projectIds}/{@code status}
 * /{@code source}/{@code keyword} are optional narrowing filters.
 */
    ChatSessionPageView listVisible(long userId, List<Long> projectIds, Integer status,
                                    Integer source, String keyword, int page, int pageSize);

    /**
 * Whitelist validation of a context payload at the ONE door (BE batch
 * : the session create above and the per-question run door
 * share this single reconciliation point — JSON object only, keys
 * limited to navigation identifiers, scalar values only, and a
 * projectId pointer must name the session's own segment. Returns the
 * payload unchanged when it passes; refuses with a named reason when
 * anything else rides in.
 */
    String normalizeContextWhitelist(String contextJson, long sessionProjectId);

    /**
 * Fold one accepted question's page anchor into the session context
 * : the question's route/objectId/executionId
 * overwrite the session's current values, origin_route is written on the
 * first fold only, and an identical replay lands NO UPDATE (R28). The
 * payload must already have passed {@link #normalizeContextWhitelist} —
 * this door never re-validates. A session row that vanished concurrently
 * is skipped silently; refreshing context must not fail a question that
 * already sealed its turn rows.
 */
    void applyQuestionContext(long sessionId, String normalizedQuestionContext);
}
