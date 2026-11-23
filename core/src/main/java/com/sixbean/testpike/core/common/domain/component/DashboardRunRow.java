package com.sixbean.testpike.core.common.domain.component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;

/**
 * Shared reading of the run row a dashboard block is judged by (project-set board).
 *
 * <p>Every per-project block on the board — journey traffic light, load-test overview —
 * decides its colour from the LATEST run inside the window, and every run table in this
 * system uses the same status vocabulary for it (1 executing, 2 finished normally,
 * 3 stopped by a guard, 4 stopped by hand / cancelled). The mapping lives here once so
 * two blocks can never disagree on what the same status means (R40).
 */
public final class DashboardRunRow {

    private DashboardRunRow() {
    }

    /** Start time of the deciding run (NULL column = no run to decide). */
    public static LocalDateTime startedAtOf(ResultSet rs, String column) throws SQLException {
        java.sql.Timestamp raw = rs.getTimestamp(column);
        return raw == null ? null : raw.toLocalDateTime();
    }

    /**
     * Light word for the deciding run: 1 executing → amber, 2 finished normally → green,
     * 3 stopped by a guard → red; 4 (stopped by hand or cancelled), a NULL status and
     * any other value → grey, because a run that was ended by a human or never resolved
     * is honestly "no verdict", not an outcome.
     */
    public static String lightOf(ResultSet rs, String statusColumn) throws SQLException {
        return switch (rs.getObject(statusColumn) == null ? 0 : rs.getInt(statusColumn)) {
            case 1 -> "amber";
            case 2 -> "green";
            case 3 -> "red";
            default -> "grey";
        };
    }
}
