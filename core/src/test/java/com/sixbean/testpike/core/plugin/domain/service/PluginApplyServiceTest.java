package com.sixbean.testpike.core.plugin.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.plugin.api.dto.AppliedPluginView;
import com.sixbean.testpike.core.plugin.api.dto.PluginApplyView;
import com.sixbean.testpike.core.plugin.api.dto.PluginView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class PluginApplyServiceTest {

    @Autowired
    private PluginService pluginService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String suffix;

    @BeforeEach
    void prepareFixturePrefix() {
        suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        jdbcTemplate.update("""
                DELETE FROM tp_project_plugin
                 WHERE project_id IN (SELECT id FROM tp_project WHERE code LIKE ?)
                """, "T212A%");
        jdbcTemplate.update("""
                DELETE FROM tp_project_skill
                 WHERE project_id IN (SELECT id FROM tp_project WHERE code LIKE ?)
                """, "T212A%");
        jdbcTemplate.update("DELETE FROM tp_skill WHERE name LIKE ?", "t212-skill-%");
        jdbcTemplate.update("DELETE FROM tp_plugin WHERE name LIKE ?", "t212-%");
        jdbcTemplate.update("DELETE FROM tp_project WHERE code LIKE ?", "T212A%");
    }

    private long newProject() {
        String code = "T212A" + suffix;
        jdbcTemplate.update("INSERT INTO tp_project (name, code) VALUES (?, ?)",
                "T212 装配夹具 " + suffix, code);
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_project WHERE code = ?", Long.class, code);
        return id;
    }

    
    private void newPublishedSkill(String name) {
        jdbcTemplate.update("INSERT INTO tp_skill (name, type, status) VALUES (?, 1, 3)", name);
    }

    private String yaml(String pluginName, List<String> skillNames, String contentPack) {
        return """
                name: %s
                industry: POS
                version: 1.0.0
                content_pack: %s
                default_skills: [%s]
                param_template:
                  region: TW
                  terminal: T01
                project_types: ["pos"]
                """.formatted(pluginName, contentPack,
                String.join(", ", skillNames.stream().map(s -> "\"" + s + "\"").toList()));
    }

    private PluginView newUploadedPlugin(String pluginName, List<String> skillNames) {
        return pluginService.register(1L, yaml(pluginName, skillNames, pluginName + "-pack"));
    }

    @Test
    void applyHappyPathMountsSkillsRecordsSnapshotAndNotes() {
        long projectId = newProject();
        String hitSkill = "t212-skill-hit-" + suffix;
        String missSkill = "t212-skill-miss-" + suffix;
        newPublishedSkill(hitSkill);
        PluginView plugin = newUploadedPlugin("t212-demo-" + suffix, List.of(hitSkill, missSkill));

        PluginApplyView view = pluginService.apply(1L, projectId, plugin.id(), false);

        assertEquals(projectId, view.projectId());
        assertEquals(plugin.id(), view.pluginId());
        assertEquals("t212-demo-" + suffix, view.pluginName());
        assertEquals(Map.of("region", "TW", "terminal", "T01"), view.paramSnapshot(),
                "param_snapshot=装配时 param_template 快照");
        assertTrue(view.mountedSkills().contains(hitSkill), "命中 SKILL 应挂载");
        assertTrue(view.notes().stream().anyMatch(n -> n.contains("不存在") && n.contains(missSkill)),
                "缺失 SKILL 名应在 notes 逐枚点名（非静默，坑67④），实际=" + view.notes());
        assertTrue(view.notes().stream().anyMatch(n -> n.contains("H7") && n.contains("内容包")),
                "内容包链路应如实标注跳过（坑67⑤），实际=" + view.notes());
        assertTrue(view.notes().stream().anyMatch(n -> n.contains("项目类型")),
                "项目类型链路应如实标注跳过（坑67③），实际=" + view.notes());

        Map<String, Object> record = jdbcTemplate.queryForMap(
                "SELECT plugin_id, param_snapshot::text AS snap, applied_by"
                        + " FROM tp_project_plugin WHERE project_id = ?", projectId);
        assertEquals(plugin.id(), ((Number) record.get("plugin_id")).longValue());
        assertTrue(((String) record.get("snap")).contains("TW"),
                "param_snapshot 落库应含模板键值，实际=" + record.get("snap"));
        assertEquals(1L, ((Number) record.get("applied_by")).longValue());

        Map<String, Object> binding = jdbcTemplate.queryForMap("""
                SELECT ps.role, ps.enabled FROM tp_project_skill ps
                JOIN tp_skill s ON s.id = ps.skill_id
                 WHERE ps.project_id = ? AND s.name = ?
                """, projectId, hitSkill);
        assertEquals(3, ((Number) binding.get("role")).intValue(), "挂载 role=3 复用既有口径");
        assertEquals(1, ((Number) binding.get("enabled")).intValue());

        Integer audits = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_audit_log WHERE object_type = 'project_plugin'"
                        + " AND action = 'apply'", Integer.class);
        assertTrue(audits != null && audits >= 1, "apply 成功应落审计行");
    }

    @Test
    void applyMissingProjectRejected404() {
        PluginView plugin = newUploadedPlugin("t212-demo-" + suffix, List.of());
        ServiceException ex = assertThrows(ServiceException.class,
                () -> pluginService.apply(1L, 999_999_999L, plugin.id(), false));
        assertEquals(40401, ex.getCode(), "项目缺应 40401，实际消息=" + ex.getMessage());
        assertTrue(ex.getMessage().contains("项目"), "人话应点「项目」，实际=" + ex.getMessage());
    }

    @Test
    void applyMissingPluginRejected404() {
        long projectId = newProject();
        ServiceException ex = assertThrows(ServiceException.class,
                () -> pluginService.apply(1L, projectId, 999_999_999L, false));
        assertEquals(40401, ex.getCode(), "插件缺应 40401，实际消息=" + ex.getMessage());
        assertTrue(ex.getMessage().contains("插件"), "人话应点「插件」，实际=" + ex.getMessage());
    }

    @Test
    void applyDisabledPluginRejected403() {
        long projectId = newProject();
        PluginView plugin = newUploadedPlugin("t212-demo-" + suffix, List.of());
        pluginService.setEnabled(1L, plugin.id(), false);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> pluginService.apply(1L, projectId, plugin.id(), false));
        assertEquals(40301, ex.getCode(), "disabled apply 应 40301（IT-H8-02），实际消息=" + ex.getMessage());
        assertTrue(ex.getMessage().contains("停用"), "人话应点「停用」，实际=" + ex.getMessage());
    }

    @Test
    void applyTwiceRejected409IdempotentHint() {
        long projectId = newProject();
        PluginView plugin = newUploadedPlugin("t212-demo-" + suffix, List.of());
        pluginService.apply(1L, projectId, plugin.id(), false);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> pluginService.apply(1L, projectId, plugin.id(), false));
        assertEquals(40904, ex.getCode(), "重复 apply 应 40904 幂等提示（坑67②），实际消息=" + ex.getMessage());
        assertTrue(ex.getMessage().contains("已装配"), "人话应含「已装配」，实际=" + ex.getMessage());
        assertEquals(1, jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM tp_project_plugin WHERE project_id = ? AND plugin_id = ?",
                        Integer.class, projectId, plugin.id()),
                "拒后装配记录不得多行");
    }

    // ------------------------------------------------------------------
    // ------------------------------------------------------------------

    @Test
    void uninstallDeletesMountRowOnlyAndKeepsAssetsAllowsReapply() {
        long projectId = newProject();
        String hitSkill = "t212-skill-hit-" + suffix;
        newPublishedSkill(hitSkill);
        PluginView plugin = newUploadedPlugin("t212-demo-" + suffix, List.of(hitSkill));
        pluginService.apply(1L, projectId, plugin.id(), false);

        pluginService.uninstall(1L, projectId, plugin.id());

        assertEquals(0, jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM tp_project_plugin WHERE project_id = ? AND plugin_id = ?",
                        Integer.class, projectId, plugin.id()),
                "卸载=摘除 tp_project_plugin 装配行");
        assertEquals(1, jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM tp_project_skill ps JOIN tp_skill s ON s.id = ps.skill_id"
                                + " WHERE ps.project_id = ? AND s.name = ?",
                        Integer.class, projectId, hitSkill),
                "已挂 SKILL 行不回收（卸载=不动已生成资产，IT-H8-02）");
        Integer audits = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_audit_log WHERE object_type = 'project_plugin'"
                        + " AND action = 'uninstall'", Integer.class);
        assertTrue(audits != null && audits >= 1, "uninstall 成功应落审计行");
        assertDoesNotThrow(() -> pluginService.apply(1L, projectId, plugin.id(), false),
                "卸载后 reapply 应放行（UNIQUE 仅行在约束）");
    }

    @Test
    void uninstallMissingProjectRejected404() {
        PluginView plugin = newUploadedPlugin("t212-demo-" + suffix, List.of());
        ServiceException ex = assertThrows(ServiceException.class,
                () -> pluginService.uninstall(1L, 999_999_999L, plugin.id()));
        assertEquals(40401, ex.getCode(), "项目缺应 40401，实际消息=" + ex.getMessage());
        assertTrue(ex.getMessage().contains("项目"), "人话应点「项目」，实际=" + ex.getMessage());
    }

    @Test
    void uninstallMissingPluginRejected404() {
        long projectId = newProject();
        ServiceException ex = assertThrows(ServiceException.class,
                () -> pluginService.uninstall(1L, projectId, 999_999_999L));
        assertEquals(40401, ex.getCode(), "插件缺应 40401，实际消息=" + ex.getMessage());
        assertTrue(ex.getMessage().contains("插件"), "人话应点「插件」，实际=" + ex.getMessage());
    }

    @Test
    void uninstallNotAppliedRejected404IdempotentHint() {
        long projectId = newProject();
        PluginView plugin = newUploadedPlugin("t212-demo-" + suffix, List.of());
        ServiceException ex = assertThrows(ServiceException.class,
                () -> pluginService.uninstall(1L, projectId, plugin.id()));
        assertEquals(40401, ex.getCode(), "未装配卸载应 40401（幂等提示，判序承 apply 链路），实际消息=" + ex.getMessage());
        assertTrue(ex.getMessage().contains("未装配"), "人话应含「未装配」，实际=" + ex.getMessage());
    }

    @Test
    void applyBuiltInSampleAllowed() {
        long projectId = newProject();
        PluginView builtin = pluginService.list(null, null).stream()
                .filter(v -> "pos-retail".equals(v.name())).findFirst().orElseThrow();
        assertNotNull(builtin, "V60 seed 内置 pos-retail 应在");

        PluginApplyView view = pluginService.apply(1L, projectId, builtin.id(), false);
        assertTrue(view.notes().stream().anyMatch(n -> n.contains("SKILL")),
                "内置样例 default_skills 缺失名应点名（seed 三枚 SKILL 不存在），实际=" + view.notes());
    }

    // ------------------------------------------------------------------
    // ------------------------------------------------------------------

    @Test
    void listAppliedShowsPluginNameEnabledSnapshotAndAppliedAt() {
        long projectId = newProject();
        PluginView plugin = newUploadedPlugin("t212-demo-" + suffix, List.of());
        pluginService.apply(1L, projectId, plugin.id(), false);

        List<AppliedPluginView> rows = pluginService.listApplied(projectId);
        assertEquals(1, rows.size(), "apply 后读路径应可见装配行");
        AppliedPluginView row = rows.get(0);
        assertEquals(plugin.id(), row.pluginId());
        assertEquals("t212-demo-" + suffix, row.pluginName(), "插件名 JOIN 回查（前端清单展示面）");
        assertTrue(row.enabled(), "seed/上传默认 enabled=true 回显");
        assertEquals(Map.of("region", "TW", "terminal", "T01"), row.paramSnapshot(),
                "param_snapshot=装配时快照（IT-H8-02 可查语义的端点面）");
        assertNotNull(row.appliedAt(), "applied_at 非空（to_char String 基径，禁 LocalDateTime 漂移）");
        assertTrue(row.appliedAt().matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"),
                "applied_at 格式=YYYY-MM-DD HH24:MI:SS，实际=" + row.appliedAt());
    }

    @Test
    void listAppliedEmptyAfterUninstall() {
        long projectId = newProject();
        PluginView plugin = newUploadedPlugin("t212-demo-" + suffix, List.of());
        pluginService.apply(1L, projectId, plugin.id(), false);
        pluginService.uninstall(1L, projectId, plugin.id());

        assertTrue(pluginService.listApplied(projectId).isEmpty(),
                "卸载后读路径应为空集（摘行即不可见）");
    }

    @Test
    void listAppliedMissingProjectReturnsEmptyNotError() {
        assertTrue(pluginService.listApplied(999_999_999L).isEmpty(),
                "未装配/项目缺一律空集，读路径零判定");
    }
}
