package com.sixbean.testpike.aiservice.aichat.domain.component;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.SkillAssemblyRemoteItem;
import com.sixbean.testpike.aiservice.llm.pipeline.DomainExperienceLine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Domain-experience read door .
 * Real slot database rows (tp_ai_ctx_memory / tp_skill / tp_skill_usage),
 * CoreGateway stubbed = L2 isolation of the remote assembly door
 * Asserted contracts: ① own-project memory + in-scope SKILL usage ledger land
 * in the section; ② another project's memory and an out-of-assembly SKILL
 * ④ a failing door is fail-soft (turn keeps its head, never breaks);
 * ⑤ the K8 line cap is read hot per call, no caching.
 */
@SpringBootTest
class DomainExperienceReaderTest {

    /** Synthetic project segment nobody else touches (memory rows carry no FK). */
    private static final long SYNTHETIC_PROJECT = 988_999_997L;

    @Autowired
    private DomainExperienceReader reader;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private CoreGateway coreGateway;

    private long foreignProject;
    private final List<Long> insertedMemoryIds = new ArrayList<>();
    private final List<Long> insertedSkillIds = new ArrayList<>();
    private final List<Long> insertedUsageIds = new ArrayList<>();

    @BeforeEach
    void resolveRealProjects() {
        // The experience bucket under test is the synthetic project (deterministic:
        // no factory rows live there); the "other bucket" needs real tp_project
        // rows so the SKILL FK holds — self-built fixtures, removed in @AfterEach
        // (tp_project is a user-created domain with no factory seed).
        jdbcTemplate.update("INSERT INTO tp_project (name, code) "
                + "VALUES ('经验链测试甲项目', 'XPE'), ('经验链测试乙项目', 'XPF')");
        List<Long> projects = jdbcTemplate.queryForList(
                "SELECT id FROM tp_project WHERE code IN ('XPE','XPF') ORDER BY id",
                Long.class);
        assertEquals(2, projects.size(), "fixture projects created");
        foreignProject = projects.get(1);
    }

    @AfterEach
    void cleanOwnFixtures() {
        for (long id : insertedUsageIds) {
            jdbcTemplate.update("DELETE FROM tp_skill_usage WHERE id = ?", id);
        }
        for (long id : insertedSkillIds) {
            jdbcTemplate.update("DELETE FROM tp_skill WHERE id = ?", id);
        }
        for (long id : insertedMemoryIds) {
            jdbcTemplate.update("DELETE FROM tp_ai_ctx_memory WHERE id = ?", id);
        }
        insertedUsageIds.clear();
        insertedSkillIds.clear();
        insertedMemoryIds.clear();
        jdbcTemplate.update("DELETE FROM tp_project WHERE code IN ('XPE','XPF')");
    }

    private long insertMemory(long projectId, String content, long hits, String updatedAt) {
        Long id = jdbcTemplate.queryForObject(
                "INSERT INTO tp_ai_ctx_memory (project_id, content, source_ref,"
                        + " inject_status, hit_count, updated_at)"
                        + " VALUES (?, ?, '{}'::jsonb, 1, ?, ?::timestamptz) RETURNING id",
                Long.class, projectId, content, hits, updatedAt);
        insertedMemoryIds.add(id);
        return id;
    }

    private long insertPublishedSkill(Long projectIdOrNull, String name) {
        Long id = jdbcTemplate.queryForObject(
                "INSERT INTO tp_skill (project_id, name, type, status)"
                        + " VALUES (?, ?, 5, 3) RETURNING id",
                Long.class, projectIdOrNull, name);
        insertedSkillIds.add(id);
        return id;
    }

    private void insertUsage(long skillId) {
        Long id = jdbcTemplate.queryForObject(
                "INSERT INTO tp_skill_usage (skill_id, stage) VALUES (?, 1) RETURNING id",
                Long.class, skillId);
        insertedUsageIds.add(id);
    }

    private void stubCap(String capValue) {
        when(coreGateway.getSysConfig("ai.behavior.experience-max-lines")).thenReturn(capValue);
    }

    private static SkillAssemblyRemoteItem skillRow(long skillId, String name) {
        return new SkillAssemblyRemoteItem(skillId, name, 3, 0, 1, 1, "body",
                DomainExperienceLine.KIND_SKILL);
    }

    @Test
    void ownMemoryAndInScopeSkillUsageLandForeignBucketsNeverCross() {
        stubCap(null);
        insertMemory(SYNTHETIC_PROJECT, "本项目经验：断言必须校验业务码", 5, "2026-11-01");
        insertMemory(foreignProject, "他项目经验：绝不可跨桶泄漏", 9, "2026-10-30");
        long inScopeSkill = insertPublishedSkill(null, "登录流检查经验测试");
        insertUsage(inScopeSkill);
        insertUsage(inScopeSkill);
        long outOfScopeSkill = insertPublishedSkill(foreignProject, "经验外SKILL越权检查");
        insertUsage(outOfScopeSkill);
        when(coreGateway.getProjectAssembly(SYNTHETIC_PROJECT))
                .thenReturn(List.of(skillRow(inScopeSkill, "登录流检查经验测试")));

        String section = reader.sectionFor(SYNTHETIC_PROJECT);

        assertTrue(section.contains("本项目经验：断言必须校验业务码"));
        assertFalse(section.contains("他项目经验"), "memory never crosses the project bucket");
        assertTrue(section.contains("SKILL《登录流检查经验测试》"),
                "usage ledger rides the assembly view");
        assertTrue(section.contains("近 30 天调用 2 次"));
        assertFalse(section.contains("经验外SKILL越权检查"),
                "a SKILL outside this project's assembly view is never injected");
    }

    @Test
    void aProjectWithoutExperienceGetsNoSectionAtAll() {
        stubCap(null);
        when(coreGateway.getProjectAssembly(anyLong())).thenReturn(List.of());

        assertEquals("", reader.sectionFor(SYNTHETIC_PROJECT),
                "no rows = zero noise, the section is absent");
    }

    @Test
    void aFailingAssemblyDoorDegradesToNoSectionAndNeverThrows() {
        stubCap(null);
        when(coreGateway.getProjectAssembly(anyLong()))
                .thenThrow(new IllegalStateException("door down"));

        assertEquals("", reader.sectionFor(SYNTHETIC_PROJECT),
                "observation-plane failure must not take the dialog turn down");
    }

    @Test
    void theLineCapIsReadHotPerCallFromTheK8Key() {
        stubCap("1");
        insertMemory(SYNTHETIC_PROJECT, "最新一条经验", 3, "2026-11-05");
        insertMemory(SYNTHETIC_PROJECT, "较旧一条经验", 8, "2026-11-01");
        when(coreGateway.getProjectAssembly(eq(SYNTHETIC_PROJECT))).thenReturn(List.of());

        String section = reader.sectionFor(SYNTHETIC_PROJECT);

        assertTrue(section.contains("最新一条经验"), "newest-first is kept");
        assertFalse(section.contains("较旧一条经验"), "the cap cuts the tail per hot value");
        assertTrue(section.contains("另有 1 条"), "the cut is disclosed");
    }
}
