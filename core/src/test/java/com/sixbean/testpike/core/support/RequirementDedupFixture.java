package com.sixbean.testpike.core.support;

import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Single shared fixture for the requirement duplicate-gate test families.
 * Seeds two auto-tracked projects, pins and restores the ai.dedup.* hot keys
 * through the config cache, and exposes the counters both families need
 * (live requirement rows and near-miss audit rows) so no suite re-implements
 * the same plumbing. Cleanup delegates to {@link RequirementFixtureCleaner}:
 * every created row is deleted strictly by collected id and factory tables are
 * never TRUNCATED. Build one per test with {@link #fromContext} and always
 * call {@link #cleanup()} afterwards to restore the factory seed values.
 */
public final class RequirementDedupFixture {

    private static final Map<String, String> FACTORY_SEED = Map.of(
            "ai.dedup.enabled", "1",
            "ai.dedup.l2-high-score", "0.85",
            "ai.dedup.l2-mid-score", "0.60",
            "ai.dedup.candidate-top-n", "5");

    private final ProjectService projectService;
    private final JdbcTemplate jdbc;
    private final ConfigCacheHolder configCacheHolder;
    private final RequirementFixtureCleaner collected;

    private long projectA;
    private long projectB;

    private RequirementDedupFixture(ProjectService projectService, JdbcTemplate jdbc,
            ConfigCacheHolder configCacheHolder) {
        this.projectService = projectService;
        this.jdbc = jdbc;
        this.configCacheHolder = configCacheHolder;
        this.collected = new RequirementFixtureCleaner(jdbc);
    }

    /** Assemble from the running Spring context and seed both projects. */
    public static RequirementDedupFixture fromContext(ApplicationContext context,
            String codePrefix, String displayName) {
        RequirementDedupFixture fixture = new RequirementDedupFixture(
                context.getBean(ProjectService.class),
                context.getBean(JdbcTemplate.class),
                context.getBean(ConfigCacheHolder.class));
        fixture.seedProjects(codePrefix, displayName);
        return fixture;
    }

    /** Create and track the two isolated projects every suite seeds first. */
    public void seedProjects(String codePrefix, String displayName) {
        projectA = createProject(codePrefix, displayName);
        projectB = createProject(codePrefix, displayName);
    }

    /** Create one tracked project; the code and display name carry a fresh uuid. */
    private long createProject(String codePrefix, String displayName) {
        String code = codePrefix + UUID.randomUUID().toString().substring(0, 8);
        long pid = projectService.create(1L,
                new ProjectCreateInput(displayName + code, code, "zh-CN", "P1", null));
        collected.trackProject(pid);
        return pid;
    }

    public long projectA() {
        return projectA;
    }

    public long projectB() {
        return projectB;
    }

    /** Raw jdbc for suite-specific inserts that must not be duplicated elsewhere. */
    public JdbcTemplate jdbc() {
        return jdbc;
    }

    /** Pin one hot key through the config cache (effective for the running service). */
    public void pin(String key, String value) {
        configCacheHolder.put(key, value);
    }

    /** Pin the three-tier gate on/off switch. */
    public void pinEnabled(boolean enabled) {
        pin("ai.dedup.enabled", enabled ? "1" : "0");
    }

    /** Pin the high and medium similarity band thresholds. */
    public void pinThresholds(String high, String mid) {
        pin("ai.dedup.l2-high-score", high);
        pin("ai.dedup.l2-mid-score", mid);
    }

    /** Register a requirement row created by the owning suite for id-based cleanup. */
    public void trackRequirement(long id) {
        collected.trackRequirement(id);
    }

    /** Count live (not soft-deleted) requirement rows under one project. */
    public long countLiveRequirements(long projectId) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM tp_requirement WHERE project_id = ? AND deleted = 0",
                Long.class, projectId);
        return count == null ? 0 : count;
    }

    /** Count DEDUP_NEAR_MISS audit rows left under one project. */
    public long countNearMissAuditRows(long projectId) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM tp_audit_log WHERE action = ? AND object_type = 'requirement'"
                        + " AND project_id = ?",
                Long.class, "DEDUP_NEAR_MISS", projectId);
        return count == null ? 0 : count;
    }

    /** Restore every ai.dedup.* factory seed value, then delete tracked rows by id. */
    public void cleanup() {
        FACTORY_SEED.forEach(configCacheHolder::put);
        collected.cleanup();
    }
}
