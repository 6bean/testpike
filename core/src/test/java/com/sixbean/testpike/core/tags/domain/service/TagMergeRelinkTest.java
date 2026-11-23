package com.sixbean.testpike.core.tags.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.tags.api.dto.TagCreateInput;
import com.sixbean.testpike.core.tags.api.dto.TagInfo;
import com.sixbean.testpike.core.tags.api.dto.TagMergeInput;
import com.sixbean.testpike.core.tags.domain.service.TagService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Merge and delete must keep every downstream tag binding alive
 * .
 * <p>Before this segment the merge transaction only re-pointed tp_case_tag:
 * a skill bound to the source tag made the final DELETE hit the tp_skill_tag
 * foreign key (NO ACTION) and fail as an opaque 500, and a batch whose pool
 * tag was the source row was left pointing at a deleted tag. The same FK also
 * hit plain deletes with skill references. These tests pin the repaired
 * shapes: the merge re-points skill bindings in the same shape as case
 * bindings (overlap first, then move), re-points the batch pool tag to the
 * surviving row, and a delete against live skill references is rejected as a
 * readable conflict naming the count and the merge path.
 * <p>Strict-testing posture: real services against the live slot database,
 * fixtures self-created and cleaned by their own ids (factory baseline
 * untouched, system-level skills are NULL-project rows by design).
 */
@SpringBootTest(classes = CoreApplication.class)
class TagMergeRelinkTest {

    @Autowired
    private TagService tagService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;
    private long skillId;

    @BeforeEach
    void createProject() {
        String code = "lnk" + System.nanoTime() % 1_000_000_000L;
        projectId = projectService.create(1L,
                new ProjectCreateInput("联动标签项目" + code, code, "zh-CN", "P1", null));
        Long created = jdbcTemplate.queryForObject(
                "INSERT INTO tp_skill (project_id, name, type, status) "
                        + "VALUES (?, ?, 4, 1) RETURNING id",
                Long.class, projectId, "联动治本技能" + code);
        skillId = created;
    }

    @AfterEach
    void cleanFixtures() {
        // Delete order follows the foreign keys: bindings before the rows
        // they reference, and only this test's own ids are touched.
        jdbcTemplate.update("DELETE FROM tp_skill_tag WHERE skill_id = ?", skillId);
        jdbcTemplate.update("DELETE FROM tp_case_tag WHERE tag_id IN "
                + "(SELECT id FROM tp_tag_dict WHERE project_id = ?)", projectId);
        jdbcTemplate.update("DELETE FROM tp_exec_batch WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_skill WHERE id = ?", skillId);
        jdbcTemplate.update("DELETE FROM tp_tag_dict WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_user_project WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
    }

    private long createTag(String name, int type) {
        TagInfo info = tagService.create(1L, new TagCreateInput(projectId, name, type));
        return info.id();
    }

    private void bindSkill(long tagId) {
        jdbcTemplate.update("INSERT INTO tp_skill_tag (skill_id, tag_id) VALUES (?, ?)",
                skillId, tagId);
    }

    private long skillBindingCount(long tagId) {
        List<Long> rows = jdbcTemplate.queryForList(
                "SELECT count(*) FROM tp_skill_tag WHERE tag_id = ?", Long.class, tagId);
        return rows.get(0);
    }

    @Test
    void mergeRepointsSkillBindingsInsteadOfHittingTheForeignKey() {
        long from = createTag("被合并技能标签", 4);
        long to = createTag("存活技能标签", 4);
        bindSkill(from);

        tagService.merge(1L, new TagMergeInput(from, to));

        assertEquals(1, skillBindingCount(to),
                "the skill binding follows the surviving tag, same shape as case bindings");
        assertEquals(0, skillBindingCount(from), "no binding may keep citing the merged-away tag");
        assertEquals(0, (int) jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_tag_dict WHERE id = ?", Integer.class, from).longValue(),
                "the source tag row is gone after a successful merge");
    }

    @Test
    void mergeDeduplicatesOverlappingSkillBinding() {
        long from = createTag("重叠来源标签", 4);
        long to = createTag("重叠存活标签", 4);
        bindSkill(to);
        bindSkill(from);

        tagService.merge(1L, new TagMergeInput(from, to));

        assertEquals(1, skillBindingCount(to),
                "a skill already bound to the survivor keeps exactly one binding");
        assertEquals(0, skillBindingCount(from), "overlap rows on the source are removed, not moved");
    }

    @Test
    void mergeRepointsBatchPoolTagToTheSurvivor() {
        long from = createTag("池来源标签", 7);
        long to = createTag("池存活标签", 7);
        jdbcTemplate.update(
                "INSERT INTO tp_exec_batch (project_id, status, name, pool_tag_id) "
                        + "VALUES (?, 1, '池引用批次', ?)", projectId, from);

        tagService.merge(1L, new TagMergeInput(from, to));

        Long pool = jdbcTemplate.queryForObject(
                "SELECT pool_tag_id FROM tp_exec_batch WHERE project_id = ? AND name = '池引用批次'",
                Long.class, projectId);
        assertEquals(to, pool,
                "the batch pool tag re-points to the surviving row; a dangling id would leave the dispatch pool unreadable");
    }

    @Test
    void deleteAgainstSkillBindingIsAReadableConflict() {
        long tag = createTag("被技能引用标签", 4);
        bindSkill(tag);

        ServiceException conflict = assertThrows(ServiceException.class,
                () -> tagService.delete(1L, tag));

        assertEquals(BizErrorCode.RESOURCE_CONFLICT, conflict.getCode());
        assertTrue(conflict.getMessage().contains("技能"),
                "the conflict message names the referencing asset family so the user knows what to detach first");
        assertEquals(1, skillBindingCount(tag), "a rejected delete must not touch the binding");
    }
}
