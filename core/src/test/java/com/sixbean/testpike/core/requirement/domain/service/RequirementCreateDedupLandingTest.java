package com.sixbean.testpike.core.requirement.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.requirement.api.dto.RequirementCreateInput;
import com.sixbean.testpike.core.requirement.api.dto.RequirementInfo;
import com.sixbean.testpike.core.support.RequirementDedupFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Landing tests for wiring the three-tier duplicate gate into the requirement
 * create service path (the single write door every entry - manual, API and
 * extraction confirm alike - must pass). Covers: an exact homonym probe is
 * refused before insert and the rejection names the existing row id while
 * pointing at the switch (hot key path + config key literal); full-width and
 * whitespace folding variants pierce the same way; another project keeps the
 * same title legal; a soft-deleted homonym never blocks a fresh create; the
 * high trgm band refuses like an exact hit; the medium band passes and leaves
 * exactly one near-miss audit row; and an operator who turned the gate off is
 * never blocked at the service door.
 * <p>Runs against the real dev database - no mocks. All shared plumbing
 * (project seeding, hot-key pinning, row counters, id-based cleanup) comes
 * from the single-source {@link RequirementDedupFixture}; factory tables are
 * never TRUNCATED.
 */
@SpringBootTest(classes = CoreApplication.class)
class RequirementCreateDedupLandingTest {

    @Autowired
    private RequirementService requirementService;
    @Autowired
    private ApplicationContext context;

    private RequirementDedupFixture fixture;
    private long projectA;
    private long projectB;

    @BeforeEach
    void seedProjects() {
        fixture = RequirementDedupFixture.fromContext(context, "ald", "查重落点项目");
        projectA = fixture.projectA();
        projectB = fixture.projectB();
    }

    @AfterEach
    void restoreFactoryKeys() {
        fixture.cleanup();
    }

    private RequirementInfo create(long projectId, String title) {
        RequirementInfo info = requirementService.create(1L, new RequirementCreateInput(
                projectId, 3, "LANDING-" + System.nanoTime(), title, null, null));
        fixture.trackRequirement(info.id());
        return info;
    }

    /** L1 exact homonym (full-width/whitespace folded) refuses the insert and names the row. */
    @Test
    void createRefusesNormalizedExactHomonymAndNamesExistingId() {
        String stored = "登录成功跳转首页" + System.nanoTime();
        RequirementInfo first = create(projectA, stored);
        long before = fixture.countLiveRequirements(projectA);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> create(projectA, "　" + stored.replace("登录", " 登录") + "　"));
        assertTrue(ex.getMessage().contains(String.valueOf(first.id())),
                "rejection must name the existing id, got: " + ex.getMessage());
        // R32 door-point guidance: path literal + config key literal.
        assertTrue(ex.getMessage().contains("系统设置"), "rejection must name the page path");
        assertTrue(ex.getMessage().contains("ai.dedup.enabled"),
                "rejection must name the config key, got: " + ex.getMessage());
        assertEquals(before, fixture.countLiveRequirements(projectA),
                "refused create must not insert any row");
    }

    /** Same title under another project is legal - the gate is project-scoped. */
    @Test
    void sameTitleInAnotherProjectPasses() {
        String title = "跨项目同名需求" + System.nanoTime();
        RequirementInfo first = create(projectA, title);
        RequirementInfo second = create(projectB, title);
        assertNotNull(second.id());
        assertTrue(second.id() != first.id());
    }

    /** A soft-deleted homonym never blocks a fresh create (deleted rows are invisible). */
    @Test
    void softDeletedHomonymDoesNotBlockCreate() {
        String title = "停用后同名重建" + System.nanoTime();
        RequirementInfo first = create(projectA, title);
        requirementService.delete(1L, first.id());
        RequirementInfo again = create(projectA, title);
        assertNotNull(again.id());
    }

    /** The high similarity band refuses like an exact hit (score pinned far below). */
    @Test
    void highBandSimilarTitleRefusesCreate() {
        String suffix = "样例" + System.nanoTime();
        create(projectA, "登录成功跳转首页" + suffix);
        fixture.pinThresholds("0.30", "0.25");
        long before = fixture.countLiveRequirements(projectA);

        assertThrows(ServiceException.class,
                () -> create(projectA, "登录成功跳转尾页" + suffix));
        assertEquals(before, fixture.countLiveRequirements(projectA),
                "high-band refusal must not insert any row");
    }

    /** The medium band lets the create land and leaves exactly one near-miss audit trail. */
    @Test
    void mediumBandPassesAndRecordsNearMissAudit() {
        String suffix = "样例" + System.nanoTime();
        create(projectA, "支付回调验签处理" + suffix);
        fixture.pinThresholds("0.98", "0.30");
        long auditBefore = fixture.countNearMissAuditRows(projectA);

        RequirementInfo landed = create(projectA, "支付回调验签重试" + suffix);
        assertNotNull(landed.id());
        assertEquals(auditBefore + 1, fixture.countNearMissAuditRows(projectA),
                "create path must evaluate the gate once and leave one near-miss audit row");
    }

    /** Operator turned the gate off: homonym creates pass through, never re-blocked. */
    @Test
    void gateDisabledLetsHomonymThrough() {
        String title = "关闭态同名放行" + System.nanoTime();
        create(projectA, title);
        fixture.pinEnabled(false);
        RequirementInfo second = create(projectA, title);
        assertNotNull(second.id());
        assertTrue(second.id() > 0);
    }
}
