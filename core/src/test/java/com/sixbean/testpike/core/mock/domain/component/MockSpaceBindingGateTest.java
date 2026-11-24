package com.sixbean.testpike.core.mock.domain.component;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.api.dto.CaseUpdateInput;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.mock.api.dto.MockSpaceInfo;
import com.sixbean.testpike.core.mock.api.dto.MockSpaceInput;
import com.sixbean.testpike.core.mock.domain.service.MockSpaceService;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.suites.api.dto.SuiteCreateInput;
import com.sixbean.testpike.core.suites.api.dto.SuiteInfo;
import com.sixbean.testpike.core.suites.api.dto.SuiteUpdateInput;
import com.sixbean.testpike.core.suites.domain.service.RegressionSuiteService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Declarative mock binding write chain for cases and regression suites.
 * <p>Cases and regression suites may name a mock space as their simulation scope,
 * but ONLY a published space of their own project. The gate refuses the three
 * illegal shapes in plain words: a space that does not exist, a space owned by
 * another project (H1 isolation — the binding must never cross a project edge),
 * and a draft/retired space (a draft has no serving rules to run against; a
 * retired space is terminal history and must not be re-entered by new bindings).
 * <p>The same gate stands behind both save chains (case create/update, suite
 * create/update) so the rejection wording can never diverge between surfaces,
 * and unbind (0) is always legal regardless of the space state — releasing a
 * binding must never be blocked by the very gate that guards making one.
 * <p>Runs against the real dev database through the real services (SpringBootTest,
 * zero mocks; same posture as MockSpaceReferenceGateBindingTest).
 */
@SpringBootTest(classes = CoreApplication.class)
class MockSpaceBindingGateTest {

    @Autowired
    private MockSpaceBindingGate gate;

    @Autowired
    private MockSpaceService spaceService;

    @Autowired
    private CaseService caseService;

    @Autowired
    private RegressionSuiteService suiteService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String uid() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private long newProject(String tag) {
        String code = "t364b" + tag + uid();
        return projectService.create(1L, new ProjectCreateInput("项目" + code, code, "zh-CN", "P1", null, null));
    }

    private long newSpace(long projectId) {
        String key = "t364bw-" + uid();
        return spaceService.create(projectId, new MockSpaceInput("Space " + key, key, null, null,
                "prefix", "none", null, null, 100, null));
    }

    /** Publish a fresh draft space and return its id (the only bindable state). */
    private long publishedSpace(long projectId) {
        long id = newSpace(projectId);
        MockSpaceInfo draft = spaceService.get(id);
        spaceService.transition(id, 1, draft.version());
        return id;
    }

    /** Publish then retire a space (terminal state — new bindings must be refused). */
    private long retiredSpace(long projectId) {
        long id = publishedSpace(projectId);
        MockSpaceInfo published = spaceService.get(id);
        spaceService.transition(id, 2, published.version());
        return id;
    }

    private long newEnv(long projectId) {
        Long eid = jdbcTemplate.queryForObject("""
                INSERT INTO tp_project_env (project_id, name, env_type, base_url, version_snapshot)
                VALUES (?, ?, ?, ?, 'v1.0.0') RETURNING id
                """, Long.class, projectId, "env-" + uid(), 2, "http://tp-local.test");
        return eid;
    }

    private long newCase(long projectId, String title) {
        return caseService.create(1L, new CaseCreateInput(projectId, title, "前置", "预期",
                1, 2, "web", 3, null, 1, null, List.of())).id();
    }

    private Long caseColumn(long caseId) {
        return jdbcTemplate.queryForObject("SELECT mock_space_id FROM tp_test_case WHERE id = ?",
                Long.class, caseId);
    }

    private Long suiteColumn(long suiteId) {
        return jdbcTemplate.queryForObject("SELECT mock_space_id FROM tp_regression_suite WHERE id = ?",
                Long.class, suiteId);
    }

    @Test
    void gatePassesUnbindAndRefusesIllegalSpacesInPlainWords() {
        long pid = newProject("g1");
        long otherPid = newProject("g1o");
        gate.requireBindable(pid, null);
        gate.requireBindable(pid, 0L); // unbind must always be legal

        long draftId = newSpace(pid);
        ServiceException draft = assertThrows(ServiceException.class, () -> gate.requireBindable(pid, draftId));
        assertTrue(draft.getMessage().contains("草稿"), "draft refusal must say why, got: " + draft.getMessage());

        long retiredId = retiredSpace(pid);
        ServiceException retired = assertThrows(ServiceException.class, () -> gate.requireBindable(pid, retiredId));
        assertTrue(retired.getMessage().contains("退役"), "retired refusal must say why, got: " + retired.getMessage());

        long foreignPublished = publishedSpace(otherPid);
        ServiceException foreign = assertThrows(ServiceException.class, () -> gate.requireBindable(pid, foreignPublished));
        assertTrue(foreign.getMessage().contains("不属于当前项目"),
                "cross-project refusal must name the ownership edge, got: " + foreign.getMessage());

        ServiceException missing = assertThrows(ServiceException.class, () -> gate.requireBindable(pid, 999999999L));
        assertTrue(missing.getMessage().contains("不存在"), "missing space refusal, got: " + missing.getMessage());

        long ownPublished = publishedSpace(pid);
        gate.requireBindable(pid, ownPublished); // the happy path must not throw
    }

    @Test
    void caseCreatePersistsPublishedBindingAndRefusesIllegalOnes() {
        long pid = newProject("c1");
        long spaceId = publishedSpace(pid);
        CaseInfo created = caseService.create(1L, new CaseCreateInput(pid, "挂仿真用例", "前置", "预期",
                1, 2, "web", 1, null, 1, null, List.of(), null, null, null, null, null, spaceId));
        assertEquals(spaceId, created.mockSpaceId(), "read-back must carry the binding (list/detail payload)");
        assertEquals(spaceId, caseColumn(created.id()), "binding must land on the row");

        long draftId = newSpace(pid);
        assertThrows(ServiceException.class, () -> caseService.create(1L,
                new CaseCreateInput(pid, "挂草稿用例", "前置", "预期",
                        1, 2, "web", 1, null, 1, null, List.of(), null, null, null, null, null, draftId)));
        long retiredId = retiredSpace(pid);
        assertThrows(ServiceException.class, () -> caseService.create(1L,
                new CaseCreateInput(pid, "挂退役用例", "前置", "预期",
                        1, 2, "web", 1, null, 1, null, List.of(), null, null, null, null, null, retiredId)));
    }

    @Test
    void caseUpdateBindsKeepsAndClearsTheDeclaration() {
        long pid = newProject("c2");
        long spaceId = publishedSpace(pid);
        long otherPublished = publishedSpace(newProject("c2o"));
        CaseInfo base = caseService.create(1L, new CaseCreateInput(pid, "更新链绑定", "前置", "预期",
                1, 2, "web", 1, null, 1, null, List.of()));
        assertEquals(null, base.mockSpaceId(), "created without declaration stays unbound");

        // null = untouched (field-level semantics)
        CaseInfo kept = caseService.update(1L, base.id(), new CaseUpdateInput("标题V2", null, null,
                null, null, null, null, null, null, null, null, null, null, null), base.version());
        assertEquals(null, kept.mockSpaceId(), "null mockSpaceId must not move the column");

        // set
        CaseInfo bound = caseService.update(1L, base.id(), new CaseUpdateInput(null, null, null,
                null, null, null, null, null, null, null, null, null, null, spaceId), kept.version());
        assertEquals(spaceId, bound.mockSpaceId());
        assertEquals(spaceId, caseColumn(bound.id()));

        // foreign project space refused on update too (same single-point gate)
        ServiceException e = assertThrows(ServiceException.class, () -> caseService.update(1L, base.id(),
                new CaseUpdateInput(null, null, null, null, null, null, null, null, null, null,
                        null, null, null, otherPublished), bound.version()));
        assertTrue(e.getMessage().contains("不属于当前项目"), "got: " + e.getMessage());

        // 0 = clear
        CaseInfo cleared = caseService.update(1L, base.id(), new CaseUpdateInput(null, null, null,
                null, null, null, null, null, null, null, null, null, null, 0L), bound.version());
        assertEquals(null, cleared.mockSpaceId(), "0 clears back to the real environment");
        assertEquals(null, caseColumn(cleared.id()));
    }

    @Test
    void suiteCreateAndUpdateCarryTheDeclarationThroughTheSameGate() {
        long pid = newProject("s1");
        long caseId = newCase(pid, "套件成员");
        long envId = newEnv(pid);
        long spaceId = publishedSpace(pid);

        SuiteInfo created = suiteService.create(1L, new SuiteCreateInput(pid, "仿真回归集",
                List.of(caseId), List.of(envId), List.of(), null, 1, null, null, null, spaceId));
        assertEquals(spaceId, created.mockSpaceId(), "suite read-back must carry the binding");
        assertEquals(spaceId, suiteColumn(created.id()));

        // unbound create stays NULL
        SuiteInfo plain = suiteService.create(1L, new SuiteCreateInput(pid, "真实回归集",
                List.of(caseId), List.of(envId), List.of(), null, 1));
        assertEquals(null, plain.mockSpaceId());

        // update: switch to a draft space refused, clear via 0 accepted
        long draftId = newSpace(pid);
        assertThrows(ServiceException.class, () -> suiteService.update(1L, created.id(),
                new SuiteUpdateInput("仿真回归集", List.of(caseId), List.of(envId),
                        List.of(), null, 1, null, null, null, draftId)));
        SuiteInfo cleared = suiteService.update(1L, created.id(),
                new SuiteUpdateInput("仿真回归集", List.of(caseId), List.of(envId),
                        List.of(), null, 1, null, null, null, 0L));
        assertEquals(null, cleared.mockSpaceId(), "0 unbinds the suite declaration");
        assertEquals(null, suiteColumn(cleared.id()));
    }

    /**
     * The gate must raise exactly the single-source refusal sentences (R63
     * three elements), not ad-hoc strings built at the throw site. A stand-in
     * jdbc returning canned rows keeps this wiring check free of real writes.
     */
    @Test
    void gateRefusalsCarryTheSingleSourceThreeElementSentences() {
        MockSpaceBindingGate draftGate = new MockSpaceBindingGate(new CannedRowJdbcTemplate(1L, 0));
        ServiceException draft = assertThrows(ServiceException.class,
                () -> draftGate.requireBindable(1L, 417L));
        assertEquals(MockRuleRefusals.bindingSpaceDraft(417L), draft.getMessage());

        MockSpaceBindingGate missingGate = new MockSpaceBindingGate(new CannedRowJdbcTemplate(null, null));
        ServiceException missing = assertThrows(ServiceException.class,
                () -> missingGate.requireBindable(1L, 417L));
        assertTrue(missing.getMessage().contains("417"),
                "missing-space refusal must echo the submitted id, got: " + missing.getMessage());

        MockSpaceBindingGate retiredGate = new MockSpaceBindingGate(new CannedRowJdbcTemplate(1L, 2));
        ServiceException retired = assertThrows(ServiceException.class,
                () -> retiredGate.requireBindable(1L, 417L));
        assertEquals(MockRuleRefusals.bindingSpaceRetired(417L), retired.getMessage());
    }
}
