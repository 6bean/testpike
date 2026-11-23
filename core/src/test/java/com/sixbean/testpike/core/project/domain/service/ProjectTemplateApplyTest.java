package com.sixbean.testpike.core.project.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import com.sixbean.testpike.core.support.SkillSuiteSupport;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.api.dto.ProjectTemplateContent;
import com.sixbean.testpike.core.project.api.dto.ProjectTemplateCreateInput;
import com.sixbean.testpike.core.project.api.dto.ProjectTemplateInfo;
import com.sixbean.testpike.core.project.api.dto.TemplateEnvItem;
import com.sixbean.testpike.core.project.api.dto.TemplateTagItem;
import com.sixbean.testpike.core.skill.api.dto.SkillCreateInput;
import com.sixbean.testpike.core.skill.api.dto.SkillInfo;
import com.sixbean.testpike.core.skill.domain.service.SkillService;
import com.sixbean.testpike.core.project.domain.service.ProjectTemplateService;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class ProjectTemplateApplyTest {

    @Autowired
    private ProjectService projectService;
    @Autowired
    private ProjectTemplateService templateService;
    @Autowired
    private SkillService skillService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Skill/project fixtures tracked through the single-source support;
     * tp_tag_dict rows are project-keyed without an FK, swept as extras. */
    private SkillSuiteSupport support;

    /** Template ids created by this run, swept by id on teardown. */
    private final java.util.List<Long> templateIds =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    private SkillSuiteSupport support() {
        if (support == null) {
            support = new SkillSuiteSupport(jdbcTemplate, List.of("tp_tag_dict"));
        }
        return support;
    }

    @BeforeEach
    @AfterEach
    void cleanTables() {
        support().sweep();
        SharedDbFixtureSweep.byIds(jdbcTemplate, "tp_project_template", templateIds);
        templateIds.clear();
    }

    /** Sweeps a crashed run's same-name system-level SKILL leftovers through
     * the single-source skill teardown before the fixture recreates them. */
    private void selfHealSystemSkill(String name) {
        java.util.List<Long> leftover = new ArrayList<>(jdbcTemplate.queryForList(
                "SELECT id FROM tp_skill WHERE name = ? AND project_id IS NULL",
                Long.class, name));
        if (!leftover.isEmpty()) {
            SharedDbFixtureSweep.skillFixtureDown(jdbcTemplate, leftover, List.of(), List.of());
        }
    }

    
    private long createPublishedSystemSkill(String name) {
        selfHealSystemSkill(name);
        return support().createPublishedSkill(skillService, null, name, "正文内容").id();
    }

    
    private long createPublishedProjectSkill(long projectId, String name) {
        return support().createPublishedSkill(skillService, projectId, name, "正文内容").id();
    }

    private long createDraftSystemSkill(String name) {
        selfHealSystemSkill(name);
        SkillInfo created = skillService.create(1L,
                new SkillCreateInput(null, name, 3, "正文内容", null, false));
        support().skills().add(created.id());
        return created.id();
    }

    /** Template create tracked for teardown; sweeps a same-name leftover
     * of a crashed run first (uk_project_template_name keeps it unique). */
    private long createTemplate(String name, String industry, ProjectTemplateContent content) {
        jdbcTemplate.update("DELETE FROM tp_project_template WHERE name = ?", name);
        long id = templateService.create(1L, new ProjectTemplateCreateInput(name, industry, content));
        templateIds.add(id);
        return id;
    }

    /** Service-path fixture project create with same-code self-heal,
     * tracked in the support ledger for the teardown sweep. */
    private long createProject(String name, String code, String lang, String priority,
            Long templateId) {
        SharedDbFixtureSweep.byProject(jdbcTemplate,
                SharedDbFixtureSweep.projectIdByCode(jdbcTemplate, code));
        long id = projectService.create(1L, new ProjectCreateInput(
                name, code, lang, priority, null, templateId));
        support().projects().add(id);
        return id;
    }

    private ProjectTemplateContent fullContent(long skillId) {
        return new ProjectTemplateContent(
                List.of(skillId),
                List.of(new TemplateTagItem("下单", 2), new TemplateTagItem("冒烟", 4)),
                List.of(new TemplateEnvItem("测试环境", 2, "http://test.example.com", "web", "chromium"),
                        new TemplateEnvItem("预发环境", 3, "http://pre.example.com", null, null)),
                List.of("建议为项目补充 1 名测试负责人"));
    }

    
    @Test
    void 带模板创建_四件套落位() {
        long skillId = createPublishedSystemSkill("用例规范SKILL");
        long templateId = createTemplate("电商行业模板", "ecommerce", fullContent(skillId));

        long projectId = createProject("模板项目", "H7A", null, null, templateId);

        Integer bound = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_project_skill WHERE project_id = ? AND skill_id = ?",
                Integer.class, projectId, skillId);
        assertEquals(1, bound, "SKILL 引用应绑定落位");

        List<String> tags = jdbcTemplate.queryForList(
                "SELECT name FROM tp_tag_dict WHERE project_id = ? ORDER BY name",
                String.class, projectId);
        assertEquals(List.of("下单", "冒烟"), tags, "标签字典预置落位");

        Integer envs = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_project_env WHERE project_id = ?", Integer.class, projectId);
        assertEquals(2, envs, "示例环境预置落位");

        Long packId = jdbcTemplate.queryForObject(
                "SELECT template_pack_id FROM tp_project WHERE id = ?", Long.class, projectId);
        assertEquals(templateId, packId, "来源模板 id 记录（template_pack_id 复用列）");

        ProjectTemplateInfo view = templateService.get(templateId);
        assertEquals(List.of("建议为项目补充 1 名测试负责人"), view.content().roleHints(),
                "角色建议回显（仅建议零自动分配，成员写路径不动）");
        assertEquals(0, (int) jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_user_project WHERE project_id = ? AND role = 1",
                Integer.class, projectId), "角色建议零自动分配");
    }

    
    @Test
    void 不选模板_行为零漂移() {
        long projectId = createProject("裸项目", "H7B", "zh-CN", "P1", null);

        assertEquals(0, (int) jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_project_skill WHERE project_id = ?", Integer.class, projectId));
        assertEquals(0, (int) jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_tag_dict WHERE project_id = ?", Integer.class, projectId));
        assertEquals(0, (int) jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_project_env WHERE project_id = ?", Integer.class, projectId));
        assertNull(jdbcTemplate.queryForObject(
                "SELECT template_pack_id FROM tp_project WHERE id = ?", Long.class, projectId));
        assertNotNull(projectService.get(projectId));
    }

    
    @Test
    void 引用未发布SKILL_40001点名_整单零写入() {
        long draftId = createDraftSystemSkill("草稿SKILL");
        long templateId = createTemplate("带草稿模板", null, fullContent(draftId));

        ServiceException ex = assertThrows(ServiceException.class, () -> projectService.create(1L,
                new ProjectCreateInput("失败项目", "H7C", null, null, null, templateId)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("草稿SKILL"), "人话点名 SKILL 名：" + ex.getMessage());
        assertEquals(0, (int) jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_project WHERE code = 'H7C'", Integer.class),
                "校验失败整单回零，不留半成品项目");
    }

    
    @Test
    void 引用非系统级SKILL_40001点名() {
        long otherProject = createProject("SKILL所属项目", "H7D", null, null, null);
        long skillId = createPublishedProjectSkill(otherProject, "项目级SKILL");
        long templateId = createTemplate("跨项目模板", null, fullContent(skillId));

        ServiceException ex = assertThrows(ServiceException.class, () -> projectService.create(1L,
                new ProjectCreateInput("失败项目2", "H7E", null, null, null, templateId)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("项目级SKILL"), "人话点名 SKILL 名：" + ex.getMessage());
    }

    
    @Test
    void 模板保存_预置生产环境被拒() {
        ServiceException ex = assertThrows(ServiceException.class, () -> templateService.create(1L,
                new ProjectTemplateCreateInput("含生产模板", null, new ProjectTemplateContent(
                        List.of(), List.of(),
                        List.of(new TemplateEnvItem("生产环境", 4, "http://prod.example.com", null, null)),
                        List.of()))));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("生产"), "人话点名：禁止预置生产环境：" + ex.getMessage());
    }
}
