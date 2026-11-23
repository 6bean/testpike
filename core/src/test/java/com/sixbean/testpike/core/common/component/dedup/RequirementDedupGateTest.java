package com.sixbean.testpike.core.common.component.dedup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.support.RequirementDedupFixture;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * Three-tier duplicate gate spectrum for requirement creates: deterministic
 * normalized key hit (including the whitespace / full-width folding variants
 * that defeated the legacy raw-title merge), pg_trgm bands (high behaves like
 * an exact hit, medium surfaces top-N neighbours and leaves a near-miss audit
 * trail, low passes quietly), the disabled switch short-circuit and the
 * write-instant TOCTOU re-check.
 * <p>Runs against the real dev database - no mocks. All shared plumbing
 * (project seeding, hot-key pinning, audit counters, id-based cleanup) comes
 * from the single-source {@link RequirementDedupFixture}; factory tables are
 * never TRUNCATED. Audit rows are additive and kept (the audit hash chain is
 * never rewritten).
 */
@SpringBootTest(classes = CoreApplication.class)
class RequirementDedupGateTest {

    @Autowired
    private DedupGate gate;
    @Autowired
    private ApplicationContext context;

    private RequirementDedupFixture fixture;
    private long projectA;
    private long projectB;

    @BeforeEach
    void seedTestProjects() {
        fixture = RequirementDedupFixture.fromContext(context, "dgate", "查重谱项目");
        projectA = fixture.projectA();
        projectB = fixture.projectB();
    }

    @AfterEach
    void cleanupFixtures() {
        fixture.cleanup();
    }

    private long insertRequirement(long projectId, String title) {
        Long id = fixture.jdbc().queryForObject(
                "INSERT INTO tp_requirement (project_id, source, title, status, version, deleted)"
                        + " VALUES (?, 3, ?, 1, 1, 0) RETURNING id",
                Long.class, projectId, title);
        long created = id != null ? id : -1;
        assertTrue(created > 0, "fixture row must be created");
        fixture.trackRequirement(created);
        return created;
    }

    @Test
    void normalizedKeyPiercesWhitespaceFullWidthAndCaseVariants() {
        long stored = insertRequirement(projectA, "ＬＯＧＩＮ　表单  校验");
        DedupVerdict verdict = gate.evaluateRequirement(projectA, "login 表单 校验");
        assertEquals(DedupLevel.EXACT, verdict.level());
        assertNotNull(verdict.matched());
        assertEquals(stored, verdict.matched().id());
    }

    @Test
    void sameTitleInAnotherProjectNeverMatches() {
        insertRequirement(projectA, "跨项目隔离标题样例");
        DedupVerdict verdict = gate.evaluateRequirement(projectB, "跨项目隔离标题样例");
        assertEquals(DedupLevel.PASS, verdict.level());
        assertFalse(verdict.blocksCreate());
    }

    @Test
    void scoreAboveHighLineBehavesLikeAnExactHit() {
        String uniq = UUID.randomUUID().toString().substring(0, 8);
        long stored = insertRequirement(projectA, "订单创建接口验证" + uniq);
        fixture.pinThresholds("0.3", "0.2");
        DedupVerdict verdict = gate.evaluateRequirement(projectA, "订单创建接口校验" + uniq);
        assertEquals(DedupLevel.HIGH, verdict.level());
        assertNotNull(verdict.matched());
        assertEquals(stored, verdict.matched().id());
        assertTrue(verdict.blocksCreate());
    }

    @Test
    void mediumBandPresentsTopNeighboursAndWritesOneNearMissAudit() {
        long stored = insertRequirement(projectA, "订单创建接口验证");
        fixture.pinThresholds("0.99", "0.05");
        long before = fixture.countNearMissAuditRows(projectA);
        DedupVerdict verdict = gate.evaluateRequirement(projectA, "订单创建接口核对");
        assertEquals(DedupLevel.MEDIUM, verdict.level());
        assertFalse(verdict.candidates().isEmpty());
        assertTrue(verdict.candidates().stream().anyMatch(c -> c.id() == stored));
        assertEquals(before + 1, fixture.countNearMissAuditRows(projectA));
    }

    @Test
    void belowMidLinePassesQuietlyWithoutAudit() {
        insertRequirement(projectA, "订单创建接口验证");
        fixture.pinThresholds("0.99", "0.9");
        long before = fixture.countNearMissAuditRows(projectA);
        DedupVerdict verdict = gate.evaluateRequirement(projectA, "支付回调超时降级");
        assertEquals(DedupLevel.PASS, verdict.level());
        assertEquals(before, fixture.countNearMissAuditRows(projectA));
    }

    @Test
    void disabledGateShortCircuitsEvenAnExactDuplicate() {
        insertRequirement(projectA, "关态直通重复标题样例");
        fixture.pinEnabled(false);
        DedupVerdict verdict = gate.evaluateRequirement(projectA, "关态直通重复标题样例");
        assertEquals(DedupLevel.PASS, verdict.level());
    }

    @Test
    void numberedVariantsStayVisibleButNeverRefuseAtTheHighLine() {
        // Live-incident shape : a project enumerating
        // numbered requirements (-2-, then -25-) was refused because the
        // trigram score of "-2-" vs "-25-" clears the high line while the
        // digit sequences differ. Different numbers = different items: the
        // fuzzy high line must never refuse them; near-miss audit keeps them
        // visible (medium band), so no information is silently swallowed.
        insertRequirement(projectA, "缺口需求点-2-25987a82");
        DedupVerdict verdict = gate.evaluateRequirement(projectA, "缺口需求点-25-25987a82");
        assertNotEquals(DedupLevel.HIGH, verdict.level(),
                "digit-divergent titles must not refuse a create at the high line");
        assertFalse(verdict.blocksCreate());
    }

    @Test
    void writeInstantRecheckSeesRowLandedAfterEvaluation() {
        fixture.pinThresholds("0.99", "0.9");
        DedupVerdict before = gate.evaluateRequirement(projectA, "落库复检竞态窗口样例");
        assertEquals(DedupLevel.PASS, before.level());
        long raced = insertRequirement(projectA, "落库复检竞态窗口样例");
        Optional<DedupCandidate> recheck = gate.recheckRequirementL1(projectA, "  落库复检竞态窗口样例  ");
        assertTrue(recheck.isPresent());
        assertEquals(raced, recheck.get().id());
    }
}
