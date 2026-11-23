package com.sixbean.testpike.aiservice.aichat.domain.component;

/**
 * Outcome of reading the replay timeline behind a task-pinned dialog turn
 * . Two shapes only:
 * <ul>
 * <li>{@link #NONE} — the question pinned no execution: the turn behaves
 * exactly as before this feature;</li>
 * <li>{@link #of} — a block for the system prompt. It is either the step
 * timeline digest (step lines + evidence-slot presence) or one honest
 * disclaimer line when the timeline could not be read — the disclaimer
 * rides the prompt on purpose, so the model tells the user it cannot
 * locate steps instead of inventing them.</li>
 * </ul>
 *
 * @param block prompt text ("" = nothing to add)
 */
public record TimeTravelContext(String block) {

    /** No task pointer on this turn. */
    public static final TimeTravelContext NONE = new TimeTravelContext("");

    /** Digest or honest disclaimer — anything non-empty rides the prompt. */
    public static TimeTravelContext of(String block) {
        return new TimeTravelContext(block == null ? "" : block);
    }

    public boolean present() {
        return !block.isEmpty();
    }
}
