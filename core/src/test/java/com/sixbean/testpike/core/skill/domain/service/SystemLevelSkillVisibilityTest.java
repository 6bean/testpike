package com.sixbean.testpike.core.skill.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.skill.api.dto.ProjectSkillBindingInput;
import com.sixbean.testpike.core.skill.api.dto.ProjectSkillBindingItem;
import com.sixbean.testpike.core.skill.api.dto.SkillAssemblyItem;
import com.sixbean.testpike.core.skill.api.dto.SkillCreateInput;
import com.sixbean.testpike.core.skill.api.dto.SkillInfo;
import com.sixbean.testpike.core.skill.api.dto.SkillQuery;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import com.sixbean.testpike.core.support.SkillSuiteSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AR batch: system-level SKILL assets visible and assembled by default.
 * <p>Mirror of SystemLevelToolVisibilityTest on the SKILL domain: the project-
 * filtered list must equal "this project ∪ system level (project_id IS NULL)",
 * and the S5 assembly view must include published system-level skills with no
 * binding rows, with explicit enabled=0 override rows as the only opt-out.
 * <p>Pass-through defaults for system skills without a binding row:
 * role=3 (general), priority=0, enabled=1.
 * <p>Real dev database, no mocks. Base shape = SkillConsumptionServiceTest.
 */
@SpringBootTest(classes = CoreApplication.class)
class SystemLevelSkillVisibilityTest {

    @Autowired
    private SkillService skillService;

    @Autowired
    private ProjectSkillService projectSkillService;

    @Autowired
    private SkillConsumptionService skillConsumptionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private SkillSuiteSupport support;

    @org.junit.jupiter.api.BeforeEach
    void prepareVisibilitySuite() {
        support = new SkillSuiteSupport(jdbcTemplate, List.of("tp_tag_dict"));
        support.sweep();
    }

    @org.junit.jupiter.api.AfterEach
    void sweepLegacyLeftovers() {
        support.sweep();
    }

    // ---- helpers (same shape as SkillConsumptionServiceTest) ----


    private SkillInfo createPublishedSkill(Long projectId, String name, String body) {
        return support.createPublishedSkill(skillService, projectId, name, body);
    }

    /** Draft-state skill (never submitted) — published-door negative fixture. */
    private long createDraftSkill(Long projectId, String name) {
        long id = skillService.create(1L,
                new SkillCreateInput(projectId, name, 3, "正文 " + support.token(), null, false)).id();
        support.skills().add(id);
        return id;
    }

    private List<String> namesOf(List<SkillInfo> list) {
        return list.stream().map(SkillInfo::name).toList();
    }

    // ---- ① list visibility ----

    @Test
    void listWithProjectFilterReturnsOwnUnionSystemLevel_otherProjectPrivateNeverLeaks() {
        long pidA = support.createProject("ASV01A");
        long pidB = support.createProject("ASV01B");
        createDraftSkill(null, "sys-skill");
        createDraftSkill(pidA, "a-skill");
        createDraftSkill(pidB, "b-skill");

        var pageA = skillService.list(new SkillQuery(1, 20, support.token(), null, null, pidA,
                null, null, null));
        assertEquals(2, pageA.total(), "project filter must equal own ∪ system bucket");
        assertTrue(namesOf(pageA.list()).containsAll(List.of("sys-skill", "a-skill")),
                String.join(",", namesOf(pageA.list())));
        assertFalse(namesOf(pageA.list()).contains("b-skill"),
                "H1 isolation: other project private skill must not leak");

        assertEquals(3, skillService.list(new SkillQuery(1, 20, support.token(), null, null, null,
                null, null, null)).total(), "unfiltered admin view keeps all buckets");
    }

    @Test
    void listProjectFilterExcludesSoftDeletedSystemSkill() {
        long pidA = support.createProject("ASV02");
        SkillInfo doomed = createPublishedSkill(null, "sys-recycled", "正文");
        skillService.retire(2L, doomed.id(), doomed.version());
        skillService.delete(1L, doomed.id(), skillService.get(doomed.id()).version());

        var pageA = skillService.list(new SkillQuery(1, 20, support.token(), null, null, pidA,
                null, null, null));
        assertEquals(0, pageA.total(), "deleted=0 guard must still apply to the system bucket");
    }

    // ---- ② assembly pass-through + opt-out ----

    @Test
    void assemblyIncludesPublishedSystemSkillWithDefaultPassThroughColumns() {
        long pidA = support.createProject("ASA01");
        SkillInfo sys = createPublishedSkill(null, "sys-published", "系统正文");

        List<SkillAssemblyItem> ours = skillConsumptionService.getAssemblyItems(pidA).stream()
                .filter(i -> i.skillId() == sys.id()).toList();
        assertEquals(1, ours.size(), "no binding rows: published system skill must assemble"
                + " (factory templates ride the same pass-through bucket, checked out of this set)");
        SkillAssemblyItem item = ours.get(0);
        assertEquals(sys.id(), item.skillId());
        assertEquals(3, item.role(), "pass-through default role=general (ProjectSkillRole.DEFAULT)");
        assertEquals(0, item.priority(), "pass-through default priority=0");
        assertEquals(1, item.enabled(), "pass-through is enabled by definition");
        assertEquals(sys.currentVersion(), item.version());
        assertTrue(item.body().contains("系统正文"), "body=effective body same as binding path");
    }

    @Test
    void assemblyEnabledZeroOverrideRowOptsOutThatSystemSkill() {
        long pidA = support.createProject("ASA02");
        long pidB = support.createProject("ASA02B");
        SkillInfo sys = createPublishedSkill(null, "sys-opt-out", "正文");
        projectSkillService.saveBindings(1L, pidA, new ProjectSkillBindingInput(
                List.of(new ProjectSkillBindingItem(sys.id(), 3, 0, 0)), null));

        assertTrue(skillConsumptionService.getAssemblyItems(pidA).stream()
                        .noneMatch(i -> i.skillId() == sys.id()),
                "explicit enabled=0 override removes the system skill for project A");
        assertEquals(1, skillConsumptionService.getAssemblyItems(pidB).stream()
                        .filter(i -> i.skillId() == sys.id()).count(),
                "opt-out is a per-project account: project B pass-through untouched");
    }

    @Test
    void assemblyBindingRowOverridesPassThroughDefaultsForRoleAndPriority() {
        long pidA = support.createProject("ASA03");
        SkillInfo sys = createPublishedSkill(null, "sys-bound", "正文");
        projectSkillService.saveBindings(1L, pidA, new ProjectSkillBindingInput(
                List.of(new ProjectSkillBindingItem(sys.id(), 1, 9, 1)), null));

        List<SkillAssemblyItem> items = skillConsumptionService.getAssemblyItems(pidA).stream()
                .filter(i -> i.skillId() == sys.id()).toList();
        assertEquals(1, items.size(), "bound system skill enters exactly once (no dupes)");
        assertEquals(1, items.get(0).role(), "binding row wins over pass-through default");
        assertEquals(9, items.get(0).priority());
    }

    @Test
    void assemblyDraftSystemSkillEntersNothing_publishedDoorUnloosened() {
        long pidA = support.createProject("ASA04");
        long draftId = createDraftSkill(null, "sys-draft");
        assertTrue(skillConsumptionService.getAssemblyItems(pidA).stream()
                        .noneMatch(i -> i.skillId() == draftId),
                "published gate untouched: draft system skill must never assemble");
    }

    @Test
    void assemblyProjectPrivateStillNeedsEnabledBinding_otherProjectPrivateNeverLeaks() {
        long pidA = support.createProject("ASA05");
        long pidB = support.createProject("ASA05B");
        SkillInfo aPrivate = createPublishedSkill(pidA, "a-private", "正文");
        createPublishedSkill(pidB, "b-private", "正文");

        assertTrue(skillConsumptionService.getAssemblyItems(pidA).stream()
                        .noneMatch(i -> i.skillId() == aPrivate.id()),
                "project-private skill without binding row must not assemble");
        projectSkillService.saveBindings(1L, pidA, new ProjectSkillBindingInput(
                List.of(new ProjectSkillBindingItem(aPrivate.id(), 3, 0, 1)), null));
        List<SkillAssemblyItem> items = skillConsumptionService.getAssemblyItems(pidA);
        assertEquals(1, items.stream().filter(i -> i.skillId() == aPrivate.id()).count());
        assertFalse(items.stream().anyMatch(i -> "b-private".equals(i.name())),
                "H1: other project private skill must never enter assembly");
    }
}
