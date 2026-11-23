package com.sixbean.testpike.core.skill.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.skill.api.dto.ProjectSkillBindingInput;
import com.sixbean.testpike.core.skill.api.dto.ProjectSkillBindingItem;
import com.sixbean.testpike.core.skill.api.dto.ProjectSkillItem;
import com.sixbean.testpike.core.skill.api.dto.ProjectSkillView;
import com.sixbean.testpike.core.skill.api.dto.SkillCreateInput;
import com.sixbean.testpike.core.skill.api.dto.SkillInfo;
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
class ProjectSkillServiceTest {

    @Autowired
    private ProjectSkillService projectSkillService;

    @Autowired
    private SkillService skillService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // Fixture bookkeeping lives in the shared skill-suite harness: teardown
    // deletes only the rows this suite created (tracked ids + tracked projects,
    // R37 discipline on the shared dev database) — never a blanket TRUNCATE.
    private SkillSuiteSupport support;

    @BeforeEach
    void attachBindingHarness() {
        support = new SkillSuiteSupport(jdbcTemplate, List.of());
        support.sweep();
    }

    @AfterEach
    void sweepBindingFixture() {
        support.sweep();
    }

    // ---- helpers ----

    private long createProject(String code) {
        return support.createProject(code);
    }

    /** Create a skill and walk it to published through the real service
     *  chain (create→submit→approve); ids are tracked for teardown, so no
     *  blanket table wipe is needed (R37 discipline). */
    private long createPublishedSkill(Long projectId, String name) {
        return support.createPublishedSkill(skillService, projectId, name, "正文内容").id();
    }

    private long createDraftSkill(Long projectId, String name) {
        SkillInfo created = skillService.create(1L,
                new SkillCreateInput(projectId, name, 3, "正文内容", null, false));
        support.skills().add(created.id());
        return created.id();
    }

    private static ProjectSkillBindingItem binding(long skillId, Integer role,
                                                   Integer priority, Integer enabled) {
        return new ProjectSkillBindingItem(skillId, role, priority, enabled);
    }

    private static ProjectSkillBindingInput input(List<ProjectSkillBindingItem> skills,
                                                  Long expectedVersion) {
        return new ProjectSkillBindingInput(skills, expectedVersion);
    }

    private ProjectSkillItem itemOf(ProjectSkillView view, long skillId) {
        return view.list().stream().filter(i -> i.skillId() == skillId).findFirst().orElseThrow();
    }

    private int bindingCount(long projectId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_project_skill WHERE project_id = ?",
                Integer.class, projectId);
        return n == null ? 0 : n;
    }

    private int bindAuditCount(long projectId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_audit_log WHERE object_type = 'project_skill' "
                        + "AND action = 'bind' AND object_id = ?",
                Integer.class, String.valueOf(projectId));
        return n == null ? 0 : n;
    }


    @Test
    void 全量绑定_系统级与项目级两行落库_回显role优先级启停() {
        long pid = createProject("PSK1");
        long sysSkill = createPublishedSkill(null, "系统规范");
        long prjSkill = createPublishedSkill(pid, "项目规范");

        ProjectSkillView view = projectSkillService.saveBindings(
                1L, pid, input(List.of(
                        binding(sysSkill, 1, 10, 1),
                        binding(prjSkill, 3, null, null)), null));

        assertEquals(2, view.list().size());
        ProjectSkillItem sys = itemOf(view, sysSkill);
        assertEquals(1, sys.role(), "role=1 脚本开发→stage2 注入");
        assertEquals(10, sys.priority());
        assertEquals(1, sys.enabled());
        assertNotNull(sys.boundAt(), "绑定时间回显");
        ProjectSkillItem prj = itemOf(view, prjSkill);
        assertEquals(3, prj.role());
        assertEquals(0, prj.priority(), "priority null→默认 0（V38 DEFAULT）");
        assertEquals(1, prj.enabled(), "enabled null→默认 1（启停开关默开）");
        assertTrue(view.version() > 0, "revision=绑定集非零版本");

        assertEquals(2, bindingCount(pid));
        assertEquals(1, bindAuditCount(pid), "每次成功保存恰 1 条 bind 审计");
    }


    @Test
    void 未发布SKILL拒绑_draft与retired_40001人话零写入() {
        long pid = createProject("PSK2");
        long draft = createDraftSkill(null, "草稿技能");
        long retired = createPublishedSkill(null, "已下架技能");
        skillService.retire(1L, retired,
                skillService.get(retired).version());

        ServiceException e1 = assertThrows(ServiceException.class, () ->
                projectSkillService.saveBindings(1L, pid, input(List.of(binding(draft, 1, 0, 1)), null)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, e1.getCode());
        assertTrue(e1.getMessage().contains("未发布"), "人话须含原因: " + e1.getMessage());
        assertTrue(e1.getMessage().contains("草稿技能"), "人话须点名 SKILL: " + e1.getMessage());

        assertThrows(ServiceException.class, () ->
                projectSkillService.saveBindings(1L, pid, input(List.of(binding(retired, 1, 0, 1)), null)));

        assertEquals(0, bindingCount(pid), "全部拒绝后零落库");
    }


    @Test
    void 跨项目绑定被拒_他项目项目级SKILL_40001人话() {
        long pidA = createProject("PSKA");
        long pidB = createProject("PSKB");
        long skillB = createPublishedSkill(pidB, "B项目专属");

        ServiceException e = assertThrows(ServiceException.class, () ->
                projectSkillService.saveBindings(1L, pidA, input(List.of(binding(skillB, 3, 0, 1)), null)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, e.getCode());
        assertTrue(e.getMessage().contains("B项目专属"), e.getMessage());
        assertEquals(0, bindingCount(pidA));
    }


    @Test
    void 全量替换_旧绑定解绑_空集合清空() {
        long pid = createProject("PSK3");
        long s1 = createPublishedSkill(null, "技能一");
        long s2 = createPublishedSkill(null, "技能二");
        long s3 = createPublishedSkill(pid, "技能三");

        projectSkillService.saveBindings(1L, pid,
                input(List.of(binding(s1, 1, 0, 1), binding(s2, 2, 0, 1)), null));
        assertEquals(2, bindingCount(pid));

        ProjectSkillView replaced = projectSkillService.saveBindings(1L, pid,
                input(List.of(binding(s3, 3, 5, 1)), null));
        assertEquals(1, replaced.list().size());
        assertEquals(1, bindingCount(pid), "s1/s2 随全量替换自动解绑");

        ProjectSkillView cleared = projectSkillService.saveBindings(1L, pid,
                input(List.of(), null));
        assertEquals(0, cleared.list().size());
        assertEquals(0, bindingCount(pid), "空集合=全部解绑（TC-PRJ-02 停用消失的服务层前提）");
    }


    @Test
    void 参数校验_role越界_enabled越界_重复skillId_skills为null_各40001() {
        long pid = createProject("PSK4");
        long s = createPublishedSkill(null, "合法技能");

        ServiceException roleBad = assertThrows(ServiceException.class, () ->
                projectSkillService.saveBindings(1L, pid, input(List.of(binding(s, 4, 0, 1)), null)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, roleBad.getCode());
        assertTrue(roleBad.getMessage().contains("脚本开发"), "须含可选枚举人话: " + roleBad.getMessage());

        ServiceException enabledBad = assertThrows(ServiceException.class, () ->
                projectSkillService.saveBindings(1L, pid, input(List.of(binding(s, 1, 0, 2)), null)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, enabledBad.getCode());

        ServiceException dup = assertThrows(ServiceException.class, () ->
                projectSkillService.saveBindings(1L, pid,
                        input(List.of(binding(s, 1, 0, 1), binding(s, 2, 0, 1)), null)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, dup.getCode());
        assertTrue(dup.getMessage().contains("重复"), dup.getMessage());

        ServiceException nullList = assertThrows(ServiceException.class, () ->
                projectSkillService.saveBindings(1L, pid, input(null, null)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, nullList.getCode());
        assertTrue(nullList.getMessage().contains("[]"), "提示空数组=清空语义: " + nullList.getMessage());

        ServiceException nullSkillId = assertThrows(ServiceException.class, () ->
                projectSkillService.saveBindings(1L, pid,
                        input(List.of(binding(0L, null, null, null)), null)));
        assertEquals(BizErrorCode.NOT_FOUND, nullSkillId.getCode(), "SKILL 不存在 40401");

        assertEquals(0, bindingCount(pid), "校验失败零写入");
    }

    @Test
    void 项目不存在_40401人话() {
        ServiceException e = assertThrows(ServiceException.class, () ->
                projectSkillService.saveBindings(1L, 999999L, input(List.of(), null)));
        assertEquals(BizErrorCode.NOT_FOUND, e.getCode());
        assertTrue(e.getMessage().contains("项目"), e.getMessage());
    }


    @Test
    void 乐观锁_陈旧版本40901_人话含刷新_null强覆盖兼容() {
        long pid = createProject("PSK5");
        long s = createPublishedSkill(null, "锁技能");

        ProjectSkillView v1 = projectSkillService.saveBindings(1L, pid,
                input(List.of(binding(s, 1, 0, 1)), null));
        ProjectSkillView v2 = projectSkillService.saveBindings(1L, pid,
                input(List.of(binding(s, 2, 1, 1)), v1.version()));
        assertTrue(v2.version() > v1.version(), "每次成功保存 revision 必推进");

        ServiceException stale = assertThrows(ServiceException.class, () ->
                projectSkillService.saveBindings(1L, pid,
                        input(List.of(binding(s, 3, 0, 1)), v1.version())));
        assertEquals(BizErrorCode.VERSION_CONFLICT, stale.getCode());
        assertTrue(stale.getMessage().contains("刷新"), "人话「请刷新后重试」: " + stale.getMessage());
        assertEquals(2, itemOf(projectSkillService.getBindings(pid), s).role(),
                "冲突提交零生效");

        ProjectSkillView forced = projectSkillService.saveBindings(1L, pid,
                input(List.of(binding(s, 1, 9, 1)), null));
        assertEquals(9, itemOf(forced, s).priority(), "expectedVersion=null 强覆盖（T147b 兼容口径）");
    }

    // ---- (7) Same-set replay = idempotent no-op: saving without any change must
    // not rewrite rows (no delete/insert), must keep bound-at and revision frozen,
    // and must not append an audit row ----

    @Test
    void 同集合重放保存_零写入_boundAt与revision不动_审计不追加() {
        long pid = createProject("PSK7");
        long s = createPublishedSkill(null, "幂等技能");
        ProjectSkillView v1 = projectSkillService.saveBindings(1L, pid,
                input(List.of(binding(s, 2, 3, 1)), null));
        String boundAt1 = itemOf(v1, s).boundAt();

        ProjectSkillView v2 = projectSkillService.saveBindings(1L, pid,
                input(List.of(binding(s, 2, 3, 1)), v1.version()));

        assertEquals(v1.version(), v2.version(), "same-set replay keeps revision frozen");
        assertEquals(boundAt1, itemOf(v2, s).boundAt(), "same-set replay keeps bound-at frozen (timestamp drift without a change would fabricate a modification)");
        assertEquals(1, bindAuditCount(pid), "same-set replay appends no audit row");

        // Empty-set replay on a fresh project: same no-op posture, zero writes/audit
        long pid2 = createProject("PSK8");
        ProjectSkillView empty = projectSkillService.saveBindings(1L, pid2, input(List.of(), null));
        assertEquals(0, empty.version(), "empty-set replay: version stays 0");
        assertEquals(0, bindAuditCount(pid2), "empty-set replay writes no audit row");
    }


    @Test
    void 读取绑定集_含SKILL名称与状态回显() {
        long pid = createProject("PSK6");
        long s = createPublishedSkill(pid, "回显技能");
        projectSkillService.saveBindings(1L, pid, input(List.of(binding(s, 2, 3, 0)), null));

        ProjectSkillView view = projectSkillService.getBindings(pid);
        assertEquals(1, view.list().size());
        ProjectSkillItem item = itemOf(view, s);
        assertEquals("回显技能", item.name());
        assertNotNull(item.typeLabel());
        assertEquals(2, item.role());
        assertEquals(3, item.priority());
        assertEquals(0, item.enabled(), "停用态回显（下次任务消失由 S5 装配消费）");
    }
}
