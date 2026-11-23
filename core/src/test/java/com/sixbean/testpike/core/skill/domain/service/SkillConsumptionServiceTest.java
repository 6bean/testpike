package com.sixbean.testpike.core.skill.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.skill.api.dto.ProjectSkillBindingInput;
import com.sixbean.testpike.core.skill.api.dto.ProjectSkillBindingItem;
import com.sixbean.testpike.core.skill.api.dto.SkillAssemblyItem;
import com.sixbean.testpike.core.skill.api.dto.SkillCreateInput;
import com.sixbean.testpike.core.skill.api.dto.SkillInfo;
import com.sixbean.testpike.core.skill.api.dto.SkillUsageInput;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class SkillConsumptionServiceTest {

    @Autowired
    private SkillConsumptionService skillConsumptionService;

    @Autowired
    private SkillService skillService;

    @Autowired
    private ProjectSkillService projectSkillService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // Tracker state lives in the per-suite harness; this class asserts by
    // tracked ids (never by table-wide counts), so the harness carries no
    // count snapshot and leftovers are re-swept idempotently on create.
    private SkillSuiteSupport support;

    @BeforeEach
    void attachConsumptionHarness() {
        support = new SkillSuiteSupport(jdbcTemplate, List.of());
        support.sweep();
    }

    @AfterEach
    void sweepConsumptionFixture() {
        support.sweep();
    }


    // ---- helpers ----


    
    private SkillInfo createPublishedSkill(Long projectId, String name, String body) {
        return support.createPublishedSkill(skillService, projectId, name, body);
    }

    /** Assembly-scope gate: system-level (project_id IS NULL) rows are the
     *  default-visible bucket since the AR batch; used to separate the
     *  factory baseline pass-through rows from fixture-project rows. */
    private boolean isSystemLevel(long skillId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_skill WHERE id = ? AND project_id IS NULL",
                Integer.class, skillId);
        return n != null && n == 1;
    }

    private int usageCount(long skillId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_skill_usage WHERE skill_id = ?", Integer.class, skillId);
        return n == null ? 0 : n;
    }

    private static SkillUsageInput usage(Long taskId, long skillId, Integer version,
                                         int stage, int dropped) {
        return new SkillUsageInput(taskId, skillId, version, stage, dropped);
    }


    @Test
    void 装配视图_仅已发布进候选_含正文版本快照_三键透传() {
        long pid = support.createProject("ASM1");
        SkillInfo pub = createPublishedSkill(pid, "已发布规范", "  正文内容A  ");
        SkillInfo toRetire = createPublishedSkill(null, "将被下架", "正文B");
        projectSkillService.saveBindings(1L, pid, new ProjectSkillBindingInput(List.of(
                new ProjectSkillBindingItem(pub.id(), 1, 5, 1),
                new ProjectSkillBindingItem(toRetire.id(), 3, 9, 1)), null));
        skillService.retire(2L, toRetire.id(), toRetire.version());

        List<SkillAssemblyItem> ours = skillConsumptionService.getAssemblyItems(pid).stream()
                .filter(i -> i.skillId() == pub.id() || i.skillId() == toRetire.id())
                .toList();
        assertEquals(1, ours.size(), "retired SKILL 不得进装配视图（出厂系统级模板行在本夹具集之外）");
        SkillAssemblyItem item = ours.get(0);
        assertEquals(pub.id(), item.skillId());
        assertEquals("已发布规范", item.name());
        assertEquals(1, item.role());
        assertEquals(5, item.priority());
        assertEquals(1, item.enabled());
        assertEquals(pub.currentVersion(), item.version(), "version=消费时 current_version 快照");
        assertNotNull(item.body());
        assertTrue(item.body().contains("正文内容A"), "body=生效正文全量（装配注入源，不截断）");
    }

    @Test
    void 装配视图_读取序优先级降序同序按id升序() {
        long pid = support.createProject("ASM2");
        long low = createPublishedSkill(null, "低优先", "b1").id();
        long high = createPublishedSkill(null, "高优先", "b2").id();
        long sameHigh = createPublishedSkill(null, "同优先后建", "b3").id();
        projectSkillService.saveBindings(1L, pid, new ProjectSkillBindingInput(List.of(
                new ProjectSkillBindingItem(low, 3, 1, 1),
                new ProjectSkillBindingItem(high, 3, 9, 1),
                new ProjectSkillBindingItem(sameHigh, 3, 9, 1)), null));

        java.util.Set<Long> trio = java.util.Set.of(high, sameHigh, low);
        List<SkillAssemblyItem> items = skillConsumptionService.getAssemblyItems(pid).stream()
                .filter(i -> trio.contains(i.skillId())).toList();
        assertEquals(List.of(high, sameHigh, low),
                items.stream().map(SkillAssemblyItem::skillId).toList());
    }

    @Test
    void 装配视图_未绑定空列表_不存在项目40401() {
        long pid = support.createProject("ASM3");
        assertTrue(skillConsumptionService.getAssemblyItems(pid).stream()
                        .allMatch(i -> isSystemLevel(i.skillId())),
                "未绑定项目=零项目私有候选（系统级 published 直通=出厂基线默认可见面，与本夹具集独立核对）");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> skillConsumptionService.getAssemblyItems(999999L));
        assertEquals(BizErrorCode.NOT_FOUND, ex.getCode());
    }


    @Test
    void 留痕批量落库_含丢弃态与空任务id() {
        long pid = support.createProject("USG1");
        long skill = createPublishedSkill(pid, "规范", "正文").id();

        int written = skillConsumptionService.recordUsages(List.of(
                usage(100L, skill, 1, 2, 0),
                usage(null, skill, 1, 3, 1)));

        assertEquals(2, written);
        assertEquals(2, usageCount(skill));
        Integer droppedOnes = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_skill_usage WHERE skill_id = ? AND dropped = 1",
                Integer.class, skill);
        assertEquals(1, droppedOnes);
        Integer stage3 = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_skill_usage WHERE skill_id = ? AND stage = 3 "
                        + "AND task_id IS NULL AND skill_version = 1",
                Integer.class, skill);
        assertEquals(1, stage3);
    }

    @Test
    void 留痕已删SKILL静默跳过_返回实际写入数不抛() {
        long pid = support.createProject("USG2");
        SkillInfo doomedInfo = createPublishedSkill(pid, "将被删", "正文");
        long kept = createPublishedSkill(pid, "保留", "正文").id();
        projectSkillService.saveBindings(1L, pid, new ProjectSkillBindingInput(List.of(), null));
        SkillInfo retired = skillService.retire(2L, doomedInfo.id(), doomedInfo.version());
        skillService.delete(1L, doomedInfo.id(), retired.version());
        long doomed = doomedInfo.id();

        int written = skillConsumptionService.recordUsages(List.of(
                usage(1L, doomed, 1, 2, 0),
                usage(1L, kept, 1, 2, 0)));

        assertEquals(1, written, "已删 SKILL 的留痕静默跳过（观测面不炸主链）");
        assertEquals(0, usageCount(doomed));
        assertEquals(1, usageCount(kept));
    }


    @Test
    void 留痕形状校验族40001人话_触库前零写入() {
        long pid = support.createProject("USG3");
        long skill = createPublishedSkill(pid, "规范", "正文").id();

        ServiceException badStage = assertThrows(ServiceException.class,
                () -> skillConsumptionService.recordUsages(List.of(usage(1L, skill, 1, 9, 0))));
        assertTrue(badStage.getMessage().contains("阶段"), "人话点名阶段：" + badStage.getMessage());
        assertEquals(BizErrorCode.VALIDATION_ERROR, badStage.getCode());

        assertThrows(ServiceException.class,
                () -> skillConsumptionService.recordUsages(List.of(usage(1L, skill, 1, 2, 7))));
        assertThrows(ServiceException.class,
                () -> skillConsumptionService.recordUsages(List.of(usage(1L, 0L, 1, 2, 0))));
        assertEquals(0, usageCount(skill), "校验失败零写入");
    }

    @Test
    void 留痕空列表零写入不报错() {
        assertEquals(0, skillConsumptionService.recordUsages(List.of()));
        assertEquals(0, skillConsumptionService.recordUsages(null));
    }

    // ---- ① assembly view merges project memory (AP batch ;
    //      tp_ai_ctx_memory rows ride the same channel as kind=1) ----

    /** Fixture-built memory row, cleaned by id (R37: never TRUNCATE shipped tables). */
    private long insertMemory(long projectId, String content, int injectStatus) {
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO tp_ai_ctx_memory (project_id, content, source_ref, inject_status)
                VALUES (?, ?, '[]'::jsonb, ?) RETURNING id
                """, Long.class, projectId, content, injectStatus);
        return id;
    }

    @Test
    void 装配视图并入项目记忆_停用与跨项目不进_最新在前() {
        long pid = support.createProject("MEM1");
        long other = support.createProject("MEM2");
        long systemSkill = createPublishedSkill(null, "系统规范", "断言必须显式").id();
        long m1 = insertMemory(pid, "接口命名用驼峰", 1);
        long m2 = insertMemory(pid, "禁用 sleep 硬等待", 1);
        long foreign = insertMemory(other, "别家项目约定", 1);
        long disabled = insertMemory(pid, "操作员已停用条目", 0);
        try {
            java.util.Set<Long> ours = java.util.Set.of(systemSkill, m1, m2);
            List<SkillAssemblyItem> items = skillConsumptionService.getAssemblyItems(pid);
            List<SkillAssemblyItem> ourItems = items.stream()
                    .filter(i -> ours.contains(i.skillId())).toList();

            assertEquals(List.of(systemSkill, m2, m1),
                    ourItems.stream().map(SkillAssemblyItem::skillId).toList(),
                    "读取序=SKILL 在前、记忆按 id 降序（最新在前）");
            SkillAssemblyItem memory = ourItems.get(1);
            assertEquals(1, memory.kind(), "记忆条目 kind=1 判别");
            assertEquals(3, memory.role(), "记忆=通用角色，全阶段可注入");
            assertEquals(1, memory.enabled());
            assertEquals(0, memory.version());
            assertEquals("禁用 sleep 硬等待", memory.body());
            assertEquals(0, ourItems.get(0).kind(), "SKILL 条目 kind=0");
            assertTrue(items.stream().noneMatch(i -> "别家项目约定".equals(i.body())),
                    "跨项目记忆绝不泄漏（H1 切片）");
            assertTrue(items.stream().noneMatch(i -> "操作员已停用条目".equals(i.body())),
                    "inject_status=0 不进装配候选");
        } finally {
            jdbcTemplate.update("DELETE FROM tp_ai_ctx_memory WHERE id IN (?, ?, ?, ?)",
                    m1, m2, foreign, disabled);
        }
    }
}
