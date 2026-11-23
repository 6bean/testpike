package com.sixbean.testpike.aiservice.aichat.domain.component;

import java.util.List;

/**
 * Read-only probe of the server-side staged login-state rows behind one
 * staging token (screenshot capture batch piece F).
 * <p>The draft emitter asks it which names the transient slot actually holds
 * and rewrites every card row onto that name domain: what the human sees and
 * ticks then equals, byte for byte, what the landing door re-checks row by
 * row. A model-transcribed lookalike (case or whitespace drift, or a plain
 * hallucination) used to ride the card and then trip the fail-closed CAS at
 * confirm time forever — the second "fake usable" shape this piece retires.
 * <p>An absent/expired slot (or a probe the core door cannot serve) answers
 * an empty list — the honest-absence rule keeps a card that could only ever
 * be refused off the screen instead of minting a proposal doomed to reject.
 */
@FunctionalInterface
public interface LoginStateStagedNames {

    /** Staged entry names of one slot; empty = nothing to align against. */
    List<String> names(String stagingToken);
}
