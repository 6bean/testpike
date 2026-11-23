package com.sixbean.testpike.core.apiaccess.domain.component;

import java.util.List;

/**
 * One family's bridge from a concrete data row back to the projects that govern it.
 *
 * <p>The governance overlay can only name the entity ("case") and the parameter carrying its id;
 * turning that id into an ownership verdict is domain knowledge, so each family registers one of
 * these and delegates to the service that already owns the rows. The door never grows per-endpoint
 * branches: it looks the entity up and asks exactly one question.
 */
public interface RowProjectLookup {

    /** Governance value {@code row_owner_entity} this bridge answers for. */
    String entity();

    /**
     * Projects gating the row named by the call. Three verdicts, never conflated:
     *
     * <ul>
     *   <li>a one-element list — the owning project; the door requires the operator to be a member
     *       of it (administrators of the alert set excepted, as everywhere on this door);</li>
     *   <li>an empty list — the row exists but belongs to no project (a system-level bucket row,
     *       readable by every logged-in user exactly like its page renders it); the door asks no
     *       membership question yet still refuses when no operator is identified;</li>
     *   <li>{@code null} — no row matched (or the row is too malformed to judge), so the door
     *       fails closed.</li>
     * </ul>
     *
     * @param rowIdValue raw argument value (path/query id), never null here
     */
    List<Long> ownerProjectIds(Object rowIdValue);
}
