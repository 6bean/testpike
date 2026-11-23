package com.sixbean.testpike.core.support;

import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Shared cleanup ledger for requirement-domain test fixtures. Collects the ids
 * of projects and requirements a test creates, then deletes every tracked row
 * by id after the test finishes (review log, version history and the
 * requirement row first, then the project with its membership rows and any
 * remaining requirement rows). Factory tables are never TRUNCATED.
 */
public class RequirementFixtureCleaner {

    private final JdbcTemplate jdbc;
    private final List<Long> requirementIds = new ArrayList<>();
    private final List<Long> projectIds = new ArrayList<>();

    public RequirementFixtureCleaner(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void trackRequirement(long id) {
        requirementIds.add(id);
    }

    public void trackProject(long id) {
        projectIds.add(id);
    }

    /** Delete every tracked row strictly by collected id and reset the ledger. */
    public void cleanup() {
        for (Long id : requirementIds) {
            jdbc.update("DELETE FROM tp_requirement_review_log WHERE requirement_id = ?", id);
            jdbc.update("DELETE FROM tp_requirement_version WHERE requirement_id = ?", id);
            jdbc.update("DELETE FROM tp_requirement WHERE id = ?", id);
        }
        requirementIds.clear();
        for (Long pid : projectIds) {
            jdbc.update("DELETE FROM tp_requirement WHERE project_id = ?", pid);
            jdbc.update("DELETE FROM tp_user_project WHERE project_id = ?", pid);
            jdbc.update("DELETE FROM tp_project WHERE id = ?", pid);
        }
        projectIds.clear();
    }
}
