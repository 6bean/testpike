package com.sixbean.testpike.core.skill.domain.service;

import com.sixbean.testpike.common.approval.ApprovalStatus;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.skill.api.dto.ProjectSkillBindingInput;
import com.sixbean.testpike.core.skill.api.dto.ProjectSkillBindingItem;
import com.sixbean.testpike.core.skill.api.dto.SkillCreateInput;
import com.sixbean.testpike.core.skill.api.dto.SkillInfo;
import com.sixbean.testpike.core.skill.api.dto.SkillQuery;
import com.sixbean.testpike.core.skill.api.dto.SkillRecycleInfo;
import com.sixbean.testpike.core.support.SkillSuiteSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class SkillRecycleTest {

    @Autowired
    private SkillService skillService;

    @Autowired
    private SkillConsumptionService skillConsumptionService;

    @Autowired
    private ProjectSkillService projectSkillService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // Fixture bookkeeping lives in the shared skill-suite harness: teardown
    // deletes only the rows this suite created (tracked ids + tracked projects,
    // R37 discipline on the shared dev database) — never a blanket TRUNCATE.
    private SkillSuiteSupport support;

    @BeforeEach
    void attachRecycleHarness() {
        support = new SkillSuiteSupport(jdbcTemplate, List.of());
        support.sweep();
    }

    @AfterEach
    void sweepRecycleFixture() {
        support.sweep();
    }

    private long createProject(String code) {
        return support.createProject(code);
    }

    private SkillInfo createSkill(long projectId, String name) {
        SkillInfo created = skillService.create(1L, new SkillCreateInput(projectId, name, 1,
                "正文 " + name, null, null));
        support.skills().add(created.id());
        return created;
    }

    private int deletedFlag(long id) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT deleted FROM tp_skill WHERE id = ?", Integer.class, id);
        return n == null ? -1 : n;
    }

    private static SkillQuery listQuery(long projectId) {
        // size 200 = worst-case-scale posture: system-level union rows (R34)
        // must never push this run's tracked rows off the first page.
        return new SkillQuery(1, 200, null, null, null, projectId, null, null, null);
    }


    @Test
    void delete_softDeletesRowIntoRecycleBin() {
        long projectId = createProject("RC07A");
        SkillInfo skill = createSkill(projectId, "回收站技能A");

        skillService.delete(1L, skill.id(), skill.version());

        assertEquals(1, deletedFlag(skill.id()), "删除后行应软删保留（deleted=1）");
        // List invisibility: asserted by tracked-id membership and the own-
        // project segment, never by table-wide counts — since the AR batch the
        // list view unions system-level skills into every project segment, and
        // those rows are legitimately visible on the shared database (R34).
        var activePage = skillService.list(listQuery(projectId));
        assertTrue(activePage.list().stream().noneMatch(r -> r.id().equals(skill.id())),
                "软删行不得出现在列表");
        assertEquals(0, activePage.list().stream()
                .filter(r -> r.projectId() != null && r.projectId() == projectId).count(),
                "本项目唯一技能软删后项目切片应为空");
        ServiceException get = assertThrows(ServiceException.class,
                () -> skillService.get(skill.id()));
        assertTrue(get.getMessage().contains("回收站"),
                "详情 404 应人话指引回收站：" + get.getMessage());
        PageResult<SkillRecycleInfo> bin = skillService.recycleBin(projectId, null, 1, 10);
        assertEquals(1, bin.total(), "回收站应见软删行");
        SkillRecycleInfo row = bin.list().get(0);
        assertEquals(skill.id(), row.id());
        assertEquals("回收站技能A", row.name());
        assertEquals(ApprovalStatus.DRAFT.code(), row.status(), "回收站行应带软删前状态");
        assertEquals(1L, row.deletedBy(), "删除人=审计反查");
        assertNotNull(row.deletedAt(), "删除时间=审计反查");
        Integer vRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_skill_version WHERE skill_id = ?", Integer.class, skill.id());
        assertEquals(1, vRows, "软删不得级联丢版本链");
    }


    @Test
    void restore_bringsSkillBackKeepingStatusAndVersion() {
        long projectId = createProject("RC07B");
        SkillInfo skill = createSkill(projectId, "还原技能B");
        int versionBefore = skill.version();
        skillService.delete(1L, skill.id(), versionBefore);

        SkillInfo restored = skillService.restore(1L, skill.id(), versionBefore);

        assertEquals(0, deletedFlag(skill.id()), "还原后应回到活跃");
        assertEquals(versionBefore, restored.version(),
                "还原=撤销删除非内容变更，不 bump version（P1 同型）");
        assertEquals(ApprovalStatus.DRAFT.code(), restored.status(), "还原保持软删前状态");
        // Restored visibility counted on the own-project segment only, with
        // system-level union rows exempted (same posture as above).
        assertEquals(1, skillService.list(listQuery(projectId)).list().stream()
                .filter(r -> r.projectId() != null && r.projectId() == projectId).count(),
                "还原后项目切片行恢复可见");
        assertEquals("还原技能B", skillService.get(skill.id()).name());
        assertEquals(0, skillService.recycleBin(projectId, null, 1, 10).total());
        Integer audits = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_audit_log WHERE object_type = 'skill' "
                        + "AND action = 'restore' AND object_id = ?",
                Integer.class, String.valueOf(skill.id()));
        assertEquals(1, audits, "还原动作应留痕恰 1 条");
    }


    @Test
    void restore_staleVersion_conflict409Human() {
        long projectId = createProject("RC08");
        SkillInfo skill = createSkill(projectId, "还原冲突C");
        skillService.delete(1L, skill.id(), skill.version());
        jdbcTemplate.update("UPDATE tp_skill SET version = version + 1 WHERE id = ?", skill.id());

        ServiceException e = assertThrows(ServiceException.class,
                () -> skillService.restore(1L, skill.id(), skill.version()));
        assertEquals(BizErrorCode.VERSION_CONFLICT, e.getCode());
        assertTrue(e.getMessage().contains("刷新"), "409 应人话含刷新指引：" + e.getMessage());
        assertEquals(1, deletedFlag(skill.id()));
    }


    @Test
    void restore_activeOrMissingSkill_404Human() {
        long projectId = createProject("RCG");
        SkillInfo skill = createSkill(projectId, "活跃守卫E");
        ServiceException e = assertThrows(ServiceException.class,
                () -> skillService.restore(1L, skill.id(), skill.version()));
        assertEquals(BizErrorCode.NOT_FOUND, e.getCode());
        assertTrue(e.getMessage().contains("回收站"), "活跃行还原应 404 人话：" + e.getMessage());
        ServiceException missing = assertThrows(ServiceException.class,
                () -> skillService.restore(1L, 999_999L, 1));
        assertEquals(BizErrorCode.NOT_FOUND, missing.getCode());
    }


    @Test
    void softDeletedSkillInvisibleAtEveryReadPoint() {
        long projectId = createProject("IT07");
        SkillInfo skill = createSkill(projectId, "失联防御D");
        skillService.submit(1L, skill.id(), skill.version());
        SkillInfo pub = skillService.approve(1L, skill.id(), skill.version() + 1);
        projectSkillService.saveBindings(1L, projectId, new ProjectSkillBindingInput(
                java.util.List.of(new ProjectSkillBindingItem(pub.id(), 1, 1, 1)), null));
        SkillInfo retired = skillService.retire(1L, pub.id(), pub.version());
        skillService.delete(1L, retired.id(), retired.version());

        var bindings = projectSkillService.getBindings(projectId);
        assertTrue(bindings.list() == null || bindings.list().stream()
                        .noneMatch(b -> b.skillId().equals(skill.id())),
                "项目绑定视图不得回显软删 SKILL");
        // ② assembly service real call: the soft-deleted skill disappears
        // from the assembly channel (system-level default rows are injected
        // by design since the AR batch, so the assertion keys on the tracked
        // skill id instead of isEmpty).
        assertTrue(skillConsumptionService.getAssemblyItems(projectId).stream()
                        .noneMatch(i -> i.skillId() == skill.id()),
                "AI 装配不得注入软删 SKILL 正文");
        ServiceException bind = assertThrows(ServiceException.class, () ->
                projectSkillService.saveBindings(1L, projectId, new ProjectSkillBindingInput(
                        java.util.List.of(new ProjectSkillBindingItem(skill.id(), 1, 1, 1)), null)));
        assertEquals(BizErrorCode.NOT_FOUND, bind.getCode());
        int written = skillConsumptionService.recordUsages(java.util.List.of(
                new com.sixbean.testpike.core.skill.api.dto.SkillUsageInput(
                        null, skill.id(), 1, 2, 0)));
        assertEquals(0, written, "软删 SKILL 的 usage 留痕应自然跳过");
        ServiceException dup = assertThrows(ServiceException.class,
                () -> createSkill(projectId, "失联防御D"));
        assertEquals(BizErrorCode.RESOURCE_CONFLICT, dup.getCode());
    }
}
