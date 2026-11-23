package com.sixbean.testpike.core.aichat.domain.component;

/**
 * The twelve SSE frame types of the dialog protocol (AO batch , adjudication
 * document (frame protocol section) — the technical form of the "never leave the
 * user waiting" contract; 'user-inject' extends the protocol for mid-turn
 * steering and 'plan' carries the multi-step plan card).
 * <p>{@code wireName} is the literal written into the SSE {@code event:} field
 * and the frame ledger CHECK; a rename here without the client decoder is a
 * contract break.
 * <p>{@link #HEARTBEAT} is transport-only: it keeps the connection warm through
 * proxies but carries no content, so it never consumes a session seq and is
 * never persisted to the ledger (replaying heartbeats would shift every
 * reconnect cursor).
 */
public enum ChatFrameType {

    /** Ack right after submit: received / routed model / retrieval started. */
    STATE("state", true),
    /** Provider thinking delta — honestly absent when the model emits none (never fabricated). */
    REASON("reason", true),
    /** Tool trajectory: tool_start / tool_progress / tool_done / retrieval / drafting. */
    TRACE("trace", true),
    /** Final-answer token delta. */
    DELTA("delta", true),
    /** Write-class draft card (object/action/target/field diff), lands only via confirmed ticket. */
    DRAFT("draft", true),
    /** Grounding citation entry (document + anchor + excerpt). */
    CITE("cite", true),
    /** Silence-period keep-alive comment frame — no seq, no ledger row, never replayed. */
    HEARTBEAT("heartbeat", false),
    /** Closing frame: usage numbers + message id + evolution observation signal. */
    DONE("done", true),
    /** Human-readable degradation, typed (provider unreachable / budget / round limit). */
    ERROR("error", true),
    /**
     * Mid-turn steering: a user message injected into a running turn.
     * Ledger-persisted on purpose — the frame is the durable proof the
     * injection was accepted (never silently dropped), and replay/reload
     * show it exactly like every other persisted frame.
     */
    USER_INJECT("user-inject", true),
    /**
     * Multi-step plan card: the step list the AI proposes before running a long
     * chain (estimated tool calls at or above the ai.chat.plan-threshold key).
     * Ledger-persisted so the plan the user confirmed — including any step they
     * edited — is still visible after a reload instead of living only in the
     * live stream.
     */
    PLAN("plan", true),
    /**
 * Clarification card : the honest question the turn ends
 * on when a key slot is missing and guessing wrong is expensive — the
 * question plus 2-4 one-click options. Ledger-persisted so a reload still
 * shows what was actually asked; the user's pick arrives as a normal
 * follow-up message in a fresh turn.
 */
    CLARIFY("clarify", true);

    private final String wireName;
    private final boolean ledgerPersisted;

    ChatFrameType(String wireName, boolean ledgerPersisted) {
        this.wireName = wireName;
        this.ledgerPersisted = ledgerPersisted;
    }

    /** Literal for the SSE {@code event:} field and the ledger CHECK. */
    @com.fasterxml.jackson.annotation.JsonValue
    public String wireName() {
        return wireName;
    }

    /** Whether a frame of this type belongs in the replayable ledger. */
    public boolean isLedgerPersisted() {
        return ledgerPersisted;
    }

    /**
     * Jackson entry point for the internal HTTP leg: the frame type always
     * rides as its wire name (same literal in the ledger, the SSE event field
     * and every JSON payload), never as the Java enum name — an enum-name
     * leak would turn "user-inject" into "user_inject" for any consumer that
     * normalizes by lowercasing, hiding the frame from the live pump.
     */
    @com.fasterxml.jackson.annotation.JsonCreator
    public static ChatFrameType fromWireName(String name) {
        for (ChatFrameType type : values()) {
            if (type.wireName.equals(name)) {
                return type;
            }
        }
        throw new IllegalArgumentException("unknown frame type: " + name);
    }
}
