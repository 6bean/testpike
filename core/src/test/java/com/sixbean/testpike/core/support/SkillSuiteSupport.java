package com.sixbean.testpike.core.support;

import com.sixbean.testpike.core.skill.domain.service.SkillService;
import com.sixbean.testpike.core.skill.api.dto.SkillCreateInput;
import com.sixbean.testpike.core.skill.api.dto.SkillInfo;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Per-suite fixture bookkeeping for the skill test families: tracked fixture
 * projects and skills, the pre-test tp_skill row snapshot used by delta count
 * assertions, a unique per-run scoping token and the teardown chain. The
 * suites compose one instance instead of copy-pasting the same scaffolding
 * (single source per R40; teardown semantics live in
 * {@link SharedDbFixtureSweep}).
 */
public final class SkillSuiteSupport {

    private final JdbcTemplate jdbc;
    private final List<String> extraProjectTables;

    /** Fixture projects created by the running suite instance. */
    private final List<Long> projects = new ArrayList<>();

    /** Skill rows created by the running suite instance. */
    private final List<Long> skills = new ArrayList<>();

    /** Object-storage keys produced by uploads/downloads of this run. */
    private final List<String> storageKeys = new ArrayList<>();

    /** Unique per-run token appended to fixture bodies for count scoping. */
    private final String token = SharedDbFixtureSweep.newToken();

    /** tp_skill row count captured after the pre-test sweep (delta baseline). */
    private int skillsBefore;

    public SkillSuiteSupport(JdbcTemplate jdbc, List<String> extraProjectTables) {
        this.jdbc = jdbc;
        this.extraProjectTables = extraProjectTables;
    }

    public String token() {
        return token;
    }

    public int skillsBefore() {
        return skillsBefore;
    }

    /** Tracked fixture projects (mutable view for suite-side bookkeeping). */
    public List<Long> projects() {
        return projects;
    }

    /** Tracked skill ids created by the suite. */
    public List<Long> skills() {
        return skills;
    }

    public List<String> storageKeys() {
        return storageKeys;
    }

    /** Live tp_skill row count on the shared dev database. */
    public int countSkills() {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM tp_skill", Integer.class);
        return n == null ? 0 : n;
    }

    /** Sweep leftovers of a crashed run, then snapshot the count baseline. */
    public void begin() {
        sweep();
        skillsBefore = countSkills();
    }

    /**
     * Remove every row this suite instance created: skills with their FK
     * children and approval notifications, then the fixture projects with
     * their project-keyed extras. Trackers are cleared, so a second call is
     * a no-op.
     */
    public void sweep() {
        SharedDbFixtureSweep.skillFixtureDown(jdbc, skills, projects, extraProjectTables);
    }

    /**
     * Create a skill through the service and walk it to published
     * (create→submit→approve), tracking the row for teardown. Single source
     * for the skill suites (R40); the operator pair matches the shipped
     * local-mode test accounts.
     */
    public SkillInfo createPublishedSkill(SkillService skillService,
            Long projectId, String name, String body) {
        SkillInfo created = skillService.create(1L,
                new SkillCreateInput(projectId, name, 3, body + " " + token, null, false));
        skills.add(created.id());
        SkillInfo submitted = skillService.submit(1L, created.id(), created.version());
        return skillService.approve(2L, submitted.id(), submitted.version());
    }

    /** Idempotent fixture-project create tracked for teardown. */
    public long createProject(String code) {
        return SharedDbFixtureSweep.createTrackedProject(jdbc, code, projects);
    }
}
