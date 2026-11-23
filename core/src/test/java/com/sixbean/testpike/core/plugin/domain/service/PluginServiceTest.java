package com.sixbean.testpike.core.plugin.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.plugin.api.dto.PluginView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class PluginServiceTest {

    @Autowired
    private PluginService pluginService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanUploadsKeepSeed() {
        // FK-order guard: PluginApplyServiceTest may leave applied rows referencing uploaded
        // plugins, so removing tp_plugin rows first throws on the tp_project_plugin FK and the
        // whole class fails depending on test-class execution order (observed tick 2026-11-19).
        // Delete referencing rows first, mirroring the prefix cleanup order of that class.
        jdbcTemplate.update("DELETE FROM tp_project_plugin WHERE plugin_id IN "
                + "(SELECT id FROM tp_plugin WHERE source = 2)");
        jdbcTemplate.execute("DELETE FROM tp_plugin WHERE source = 2");
    }

    private static String yaml(String name, String version) {
        return """
                name: %s
                industry: POS
                version: %s
                content_pack: demo-pack
                default_skills: ["d1", "d2"]
                param_template:
                  region: TW
                project_types: ["pos"]
                """.formatted(name, version);
    }

    @Test
    void registerValidPersistsAndListsBack() {
        PluginView created = pluginService.register(1L, yaml("demo-plugin", "1.0.0"));

        assertTrue(created.id() != null && created.id() > 0, "落库应返回自增 id");
        assertEquals("demo-plugin", created.name());
        assertEquals("POS", created.industry());
        assertEquals("1.0.0", created.version());
        assertEquals(2, created.source(), "上传件 source=2");
        assertTrue(created.enabled(), "默认启用（V60 裁定⑤）");
        assertEquals("demo-pack", created.manifest().get("content_pack"), "manifest 同源落列回读");
        assertEquals(2, ((List<?>) created.manifest().get("default_skills")).size());

        List<PluginView> listed = pluginService.list(null, null);
        assertEquals("demo-plugin", listed.get(listed.size() - 1).name(), "新行按 id 升序在末位");
        assertEquals(3, listed.size(), "seed 2 内置 + 1 上传");
    }

    @Test
    void registerRejectsFiveInvalidContents() {
        assertBadRequest("plain text not yaml", "YAML");
        assertBadRequest("name: x\n" + "c".repeat(64 * 1024), "64KB");
        assertBadRequest("industry: POS\nversion: 1.0.0\n", "name");
        assertBadRequest(yaml("demo-plugin", "1.0.0") + "extra_key: 1\n", "extra_key");
        assertBadRequest(yaml("demo-plugin", "1.0.0") + "hooks:\n  script: rm -rf /\n", "hooks");
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_plugin WHERE source = 2", Integer.class),
                "五拒均不得残留行");
    }

    private void assertBadRequest(String content, String keywordInMessage) {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> pluginService.register(1L, content));
        assertEquals(40001, ex.getCode(), "应为参数校验 40001，实际消息=" + ex.getMessage());
        assertTrue(ex.getMessage().contains(keywordInMessage),
                "人话消息应含「" + keywordInMessage + "」，实际=" + ex.getMessage());
    }

    @Test
    void registerBuiltinNameRejectedAsReadonly() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> pluginService.register(1L, yaml("pos-retail", "2.0.0")));
        assertEquals(40301, ex.getCode(), "内置同名应 40301，实际消息=" + ex.getMessage());
        assertTrue(ex.getMessage().contains("内置"), "人话应点「内置」，实际=" + ex.getMessage());
    }

    @Test
    void registerSameNameVersionConflict409() {
        pluginService.register(1L, yaml("demo-plugin", "1.0.0"));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> pluginService.register(1L, yaml("demo-plugin", "1.0.0")));
        assertEquals(40904, ex.getCode(), "同 name+version 应冲突 40904");
        assertTrue(ex.getMessage().contains("版本号"), "人话应提示升版本号，实际=" + ex.getMessage());
    }

    @Test
    void registerNewVersionCoexists() {
        pluginService.register(1L, yaml("demo-plugin", "1.0.0"));
        pluginService.register(1L, yaml("demo-plugin", "1.1.0"));

        List<PluginView> same = pluginService.list(null, null).stream()
                .filter(v -> v.name().equals("demo-plugin")).toList();
        assertEquals(2, same.size(), "版本链=新版本行并存允许（V60 裁定②）");
    }

    @Test
    void setEnabledIdempotentAndMissingRowNotFound() {
        PluginView created = pluginService.register(1L, yaml("demo-plugin", "1.0.0"));

        PluginView off1 = pluginService.setEnabled(1L, created.id(), false);
        PluginView off2 = pluginService.setEnabled(1L, created.id(), false);
        assertEquals(false, off1.enabled());
        assertEquals(false, off2.enabled(), "PUT 幂等：重复停用同值无错");
        assertTrue(pluginService.setEnabled(1L, created.id(), true).enabled());

        ServiceException ex = assertThrows(ServiceException.class,
                () -> pluginService.setEnabled(1L, 999_999_999L, true));
        assertEquals(40401, ex.getCode(), "缺行应 40401");
    }

    // ------------------------------------------------------------------
    // ------------------------------------------------------------------

    @Test
    void detailReturnsFullDescriptorWithRevision() {
        PluginView created = pluginService.register(1L, yaml("demo-plugin", "1.0.0"));

        PluginView detail = pluginService.detail(created.id());
        assertEquals(created.id(), detail.id());
        assertEquals("demo-plugin", detail.name());
        assertEquals("1.0.0", detail.version());
        assertEquals("demo-pack", detail.manifest().get("content_pack"), "detail 回显完整描述符");
        assertEquals(2, ((List<?>) detail.manifest().get("default_skills")).size());
        assertEquals(0, detail.revision(), "新行 revision=0（V90 DEFAULT 0）");

        ServiceException ex = assertThrows(ServiceException.class,
                () -> pluginService.detail(999_999_999L));
        assertEquals(40401, ex.getCode(), "detail 缺行应 40401");
    }

    @Test
    void updateHappyPathSyncsPromotedColumnsAndBumpsRevision() {
        PluginView created = pluginService.register(1L, yaml("demo-plugin", "1.0.0"));

        PluginView updated = pluginService.update(1L, created.id(), 0,
                yaml("demo-renamed", "2.0.0"));
        assertEquals(1, updated.revision(), "编辑成功 revision+1");
        assertEquals("demo-renamed", updated.name(), "提升列与 manifest 同源单写（V60 裁定②/坑64）");
        assertEquals("2.0.0", updated.version());
        assertEquals("demo-renamed", updated.manifest().get("name"), "manifest 内 name 与提升列一致");
        Map<String, Object> cols = jdbcTemplate.queryForMap(
                "SELECT name, version, manifest::text AS m, revision FROM tp_plugin WHERE id = ?",
                created.id());
        assertEquals("demo-renamed", cols.get("name"));
        assertEquals(1, ((Number) cols.get("revision")).intValue(), "DB 面 revision 已推进");
        assertTrue(((String) cols.get("m")).contains("2.0.0"), "DB manifest 同步无漂移");
    }

    @Test
    void updateStaleRevisionConflicts409HumanReadable() {
        PluginView created = pluginService.register(1L, yaml("demo-plugin", "1.0.0"));
        pluginService.update(1L, created.id(), 0, yaml("demo-plugin", "1.1.0"));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> pluginService.update(1L, created.id(), 0, yaml("demo-plugin", "1.2.0")));
        assertEquals(40901, ex.getCode(), "陈旧 revision 应 40901，实际消息=" + ex.getMessage());
        assertTrue(ex.getMessage().contains("当前版本 1"),
                "人话应点名当前 revision（ToolServiceImpl 先例），实际=" + ex.getMessage());

        Map<String, Object> cols = jdbcTemplate.queryForMap(
                "SELECT version, revision FROM tp_plugin WHERE id = ?", created.id());
        assertEquals("1.1.0", cols.get("version"));
        assertEquals(1, ((Number) cols.get("revision")).intValue());
    }

    @Test
    void updateInvalidDescriptorRejectedFieldwiseNoDirty() {
        PluginView created = pluginService.register(1L, yaml("demo-plugin", "1.0.0"));

        assertUpdateBadRequest(created.id(), "industry: POS\nversion: 2.0.0\n", "name");
        assertUpdateBadRequest(created.id(), yaml("demo-plugin", "2.0.0") + "extra_key: 1\n", "extra_key");
        assertUpdateBadRequest(created.id(), yaml("demo-plugin", "2.0.0")
                + "hooks:\n  script: rm -rf /\n", "hooks");
        assertUpdateBadRequest(created.id(), "not: [a\ncorrupt: {", "YAML");

        PluginView after = pluginService.detail(created.id());
        assertEquals("1.0.0", after.version(), "非法拒=不落脏（行原样）");
        assertEquals(0, after.revision(), "非法拒=revision 不推进");
    }

    private void assertUpdateBadRequest(long id, String content, String keyword) {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> pluginService.update(1L, id, 0, content));
        assertEquals(40001, ex.getCode(), "非法描述符应 40001，实际消息=" + ex.getMessage());
        assertTrue(ex.getMessage().contains(keyword),
                "人话应含「" + keyword + "」，实际=" + ex.getMessage());
    }

    @Test
    void updateBuiltinRejectedAndRenameToBuiltinRejected() {
        long builtinId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_plugin WHERE name = 'pos-retail' AND source = 1", Long.class);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> pluginService.update(1L, builtinId, 0, yaml("pos-retail", "9.9.9")));
        assertEquals(40301, ex.getCode(), "内置改拒 40301，实际消息=" + ex.getMessage());
        assertTrue(ex.getMessage().contains("内置"), "人话应点「内置」，实际=" + ex.getMessage());

        PluginView created = pluginService.register(1L, yaml("demo-plugin", "1.0.0"));
        ServiceException ex2 = assertThrows(ServiceException.class,
                () -> pluginService.update(1L, created.id(), 0, yaml("pos-retail", "9.9.9")));
        assertEquals(40301, ex2.getCode(), "改名撞内置应 40301，实际消息=" + ex2.getMessage());
    }

    @Test
    void updateSameNameVersionConflict409() {
        pluginService.register(1L, yaml("demo-plugin", "1.0.0"));
        PluginView second = pluginService.register(1L, yaml("demo-plugin", "1.1.0"));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> pluginService.update(1L, second.id(), 0, yaml("demo-plugin", "1.0.0")));
        assertEquals(40904, ex.getCode(), "编辑撞他行 name+version 应 40904，实际消息=" + ex.getMessage());
        assertTrue(ex.getMessage().contains("版本号"), "人话应提示升版本号，实际=" + ex.getMessage());
    }

    @Test
    void appliedProjectsReverseLookup() {
        String suffix = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String code = "T242A" + suffix;
        try {
            PluginView plugin = pluginService.register(1L, yaml("demo-plugin", "1.0.0"));
            PluginView other = pluginService.register(1L, yaml("other-plugin", "1.0.0"));
            jdbcTemplate.update("INSERT INTO tp_project (name, code) VALUES (?, ?)",
                    "T242 反查夹具 " + suffix, code);
            long projectId = jdbcTemplate.queryForObject(
                    "SELECT id FROM tp_project WHERE code = ?", Long.class, code);
            pluginService.apply(1L, projectId, plugin.id(), false);

            var views = pluginService.appliedProjects(plugin.id());
            assertEquals(1, views.size(), "反查应命中装配项目");
            assertEquals(projectId, views.get(0).projectId());
            assertEquals("T242 反查夹具 " + suffix, views.get(0).projectName());
            assertNotNull(views.get(0).appliedAt(), "appliedAt=to_char String 基径");

            assertTrue(pluginService.appliedProjects(other.id()).isEmpty(),
                    "无装配=空集（承 listApplied 读无锁零判定）");
        } finally {
            jdbcTemplate.update("""
                    DELETE FROM tp_project_plugin
                     WHERE project_id IN (SELECT id FROM tp_project WHERE code LIKE ?)
                    """, "T242A%");
            jdbcTemplate.update("DELETE FROM tp_project WHERE code LIKE ?", "T242A%");
        }
    }

    @Test
    void listFiltersByIndustryAndEnabled() {
        PluginView erp = pluginService.register(1L, """
                name: erp-demo
                industry: ERP
                version: 1.0.0
                """);
        pluginService.setEnabled(1L, erp.id(), false);

        List<PluginView> pos = pluginService.list("POS", null);
        assertTrue(pos.stream().allMatch(v -> v.industry().equals("POS")));
        assertTrue(pos.stream().anyMatch(v -> v.name().equals("pos-retail")), "内置 POS 应命中");

        List<PluginView> erpList = pluginService.list("ERP", null);
        assertEquals(2, erpList.size(), "seed erp + 上传 erp-demo");

        List<PluginView> enabledOnly = pluginService.list(null, true);
        assertTrue(enabledOnly.stream().allMatch(PluginView::enabled));
        assertTrue(enabledOnly.stream().noneMatch(v -> v.name().equals("erp-demo")));

        assertTrue(pluginService.list(null, false).stream()
                .anyMatch(v -> v.name().equals("erp-demo")), "enabled=false 链路只回停用行");
    }
}
