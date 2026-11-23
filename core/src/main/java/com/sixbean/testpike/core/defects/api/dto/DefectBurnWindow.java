package com.sixbean.testpike.core.defects.api.dto;

/**
 * Defect burndown readings for one project inside a dashboard window
 * (project-set quality board, consumed by the reportcenter overview feed).
 *
 * <p>Open caliber rides the dashboard precedent (status &lt;&gt; 5 = not closed;
 * 5 = closed, and its {@code updated_at} stamps the closing transition because a
 * closed defect cannot be edited before reopening). Counts come from current
 * rows — a defect reopened after the window shows as open again, which is the
 * honest net reading, not a per-transition history (no transition table exists).
 *
 * @param projectId     the project this block describes
 * @param openAtStart   defects created before the window and still open at its start
 * @param createdWithin defects created inside [from, to)
 * @param closedWithin  defects whose closing transition happened inside [from, to)
 * @param openAtEnd     defects open at the window end (invariant: start + created - closed)
 */
public record DefectBurnWindow(long projectId, long openAtStart, long createdWithin,
                               long closedWithin, long openAtEnd) {

    /** All-zero block for a project the query returned no rows for (an honest zero, not a missing row). */
    public static DefectBurnWindow zeros(long projectId) {
        return new DefectBurnWindow(projectId, 0L, 0L, 0L, 0L);
    }
}
