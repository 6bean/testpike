package com.sixbean.testpike.core.tool.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.executor.domain.component.StorageRouter;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import com.sixbean.testpike.core.support.ToolSuiteSupport;
import com.sixbean.testpike.core.tool.api.dto.ToolCreateInput;
import com.sixbean.testpike.core.tool.api.dto.ToolInfo;
import com.sixbean.testpike.core.tool.api.dto.ToolMarketDownloadView;
import com.sixbean.testpike.core.tool.api.dto.ToolMarketItem;
import com.sixbean.testpike.core.tool.api.dto.ToolMarketQuery;
import com.sixbean.testpike.core.common.domain.component.ZipPackSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <p>Fixture discipline (R37): tracked creations through {@link ToolSuiteSupport}
 * + token-scoped market queries, teardown via the shared FK walk instead of
 * the blanket TRUNCATE that used to wipe the shipped factory rows.
 */
@SpringBootTest(classes = CoreApplication.class)
class ToolMarketServiceTest {

    @Autowired
    private ToolService toolService;

    @Autowired
    private ToolMarketService toolMarketService;

    @Autowired
    private StorageRouter storageRouter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    
    private final List<String> storageKeys = new ArrayList<>();

    private ToolSuiteSupport support;

    @BeforeEach
    void openMarketSuite() {
        // Sweep a crashed run's fixture users before opening (tmt- id band).
        jdbcTemplate.update("DELETE FROM tp_user WHERE username LIKE 'tmt-%'");
        support = ToolSuiteSupport.beginSuite(jdbcTemplate, List.of("tp_tag_dict"));
    }

    @AfterEach
    void clearMarketSuite() {
        support.sweep();
        jdbcTemplate.update("DELETE FROM tp_user WHERE username LIKE 'tmt-%'");
        for (String key : storageKeys) {
            try {
                storageRouter.systemProvider().delete(key);
            } catch (Exception ignore) {
            }
        }
        storageKeys.clear();
    }


    private static ToolCreateInput doc(Long projectId, String name, String body) {
        return new ToolCreateInput(projectId, name, 1, "取数", "测试数据规范", body,
                null, null, null, null, null, null, null);
    }

    private static ToolCreateInput http(Long projectId, String name) {
        return new ToolCreateInput(projectId, name, 2, "数据", "查造数服务", null,
                null, "{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}}}", null,
                1, "{\"url\":\"http://localhost:9/svc\",\"authRef\":\"k1\"}", 30000, null);
    }

    
    private long publishedTool(long operatorId, ToolCreateInput input) {
        ToolInfo created = support.createTrackedTool(toolService, operatorId, input);
        toolService.submit(operatorId, created.id(), created.version());
        ToolInfo pending = toolService.get(created.id());
        toolService.approve(1L, created.id(), pending.version());
        return created.id();
    }


    @Test
    void 市场列表只回published_三列聚合与作者display_name正确() {
        long authorId = SharedDbFixtureSweep.seedUser(jdbcTemplate, "tmt-l", "工具作者");
        long pub = publishedTool(authorId, doc(null, "取数规范", "正文A"));
        // Draft rows must never surface on the market list.
        support.createTrackedTool(toolService, authorId, doc(null, "草稿工具", "x"));

        long proj = support.createProject("MT1");
        jdbcTemplate.update("INSERT INTO tp_project_tool (project_id, tool_id, enabled) "
                + "VALUES (?, ?, 1)", proj, pub);
        jdbcTemplate.update("INSERT INTO tp_tool_invocation (caller, tool_id, tool_name, "
                + "success, duration_ms) VALUES ('user', ?, '取数规范', 1, 10)", pub);

        // The run token rides in both fixture names only, so the market page
        // stays scoped to this run on the shared dev database.
        PageResult<ToolMarketItem> page = toolMarketService.marketList(
                new ToolMarketQuery(1, 20, support.token(), null, null, null, null));
        assertEquals(1, page.total(), "draft 不进市场");
        ToolMarketItem row = page.list().get(0);
        assertEquals(pub, row.id());
        assertEquals("工具作者", row.authorName());
        assertEquals("文档类", row.typeLabel());
        assertEquals("已发布", row.statusLabel());
        assertEquals(0, row.downloadCount());
        assertEquals(1, row.refProjectCount(), "tp_project_tool 实时聚合");
        assertEquals(1, row.call30dCount(), "tp_tool_invocation 30 天窗聚合");
        assertEquals(List.of(), row.tags(), "无标签=空集合非 null");
    }

    @Test
    void 市场筛选_keyword命中名称描述正文_type与category过滤() {
        long authorId = SharedDbFixtureSweep.seedUser(jdbcTemplate, "tmt-f", "过滤器");
        long a = publishedTool(authorId, doc(null, "支付回归规范", "共享正文内容"));
        long b = publishedTool(authorId, http(null, "登录取数工具"));

        assertEquals(1, toolMarketService.marketList(
                new ToolMarketQuery(1, 20, "支付", null, null, null, null)).total());
        assertEquals(1, toolMarketService.marketList(
                new ToolMarketQuery(1, 20, "共享正文", null, null, null, null)).total(), "keyword 命中正文");
        // type/category cross-filters narrow within the run token so shipped
        // rows can never inflate or deflate the fixture counts.
        assertEquals(1, toolMarketService.marketList(
                new ToolMarketQuery(1, 20, support.token(), 2, null, null, null)).total(), "type=可执行 仅剩登录取数");
        assertEquals(1, toolMarketService.marketList(
                new ToolMarketQuery(1, 20, support.token(), null, "取数", null, null)).total(), "category 精确过滤=a");
        assertEquals(1, toolMarketService.marketList(
                new ToolMarketQuery(1, 20, support.token(), null, "数据", null, null)).total(), "category 精确过滤=b");
        assertEquals(0, toolMarketService.marketList(
                new ToolMarketQuery(1, 20, support.token(), null, "外域", null, null)).total());
        assertNotNull(a);
        assertNotNull(b);
    }

    @Test
    void 市场排序白名单三列可排_非白名单400人话() {
        long authorId = SharedDbFixtureSweep.seedUser(jdbcTemplate, "tmt-s", "排序员");
        long small = publishedTool(authorId, doc(null, "下载少", "x"));
        long big = publishedTool(authorId, doc(null, "下载多", "x"));
        jdbcTemplate.update("UPDATE tp_tool SET download_count = 9 WHERE id = ?", big);
        jdbcTemplate.update("UPDATE tp_tool SET download_count = 1 WHERE id = ?", small);

        List<ToolMarketItem> desc = toolMarketService.marketList(
                new ToolMarketQuery(1, 20, support.token(), null, null, "downloadCount", "desc")).list();
        assertEquals(List.of(big, small), desc.stream().map(ToolMarketItem::id).toList());
        toolMarketService.marketList(
                new ToolMarketQuery(1, 20, support.token(), null, null, "refProjectCount", "asc"));
        toolMarketService.marketList(
                new ToolMarketQuery(1, 20, support.token(), null, null, "call30d", "asc"));
        ServiceException bad = assertThrows(ServiceException.class, () -> toolMarketService.marketList(
                new ToolMarketQuery(1, 20, null, null, null, "body; DROP TABLE tp_tool", "desc")));
        assertEquals(BizErrorCode.VALIDATION_ERROR, bad.getCode());
        assertTrue(bad.getMessage().contains("downloadCount"), "报错含允许清单: " + bad.getMessage());
    }


    @Test
    void 下载md_DOC正文带签URL可取_签发即download_count涨() {
        long authorId = SharedDbFixtureSweep.seedUser(jdbcTemplate, "tmt-d", "下载作者");
        long id = publishedTool(authorId, doc(null, "下载涨量工具", "正文ABC"));

        ToolMarketDownloadView view = toolMarketService.download(id, "md");
        storageKeys.add(view.objectKey());
        assertNotNull(view.url(), "presign 带签 URL");
        assertEquals(id, view.toolId());
        // The tracked name carries the run token, so the expected file name
        // is read back from the service instead of hard-coded.
        assertEquals(toolService.get(id).name() + ".md", view.fileName());
        byte[] fetched = storageRouter.systemProvider().get(view.objectKey());
        assertEquals("正文ABC", new String(fetched, StandardCharsets.UTF_8));

        Integer cnt = jdbcTemplate.queryForObject(
                "SELECT download_count FROM tp_tool WHERE id = ?", Integer.class, id);
        assertEquals(1, cnt);
        toolMarketService.download(id, "md");
        cnt = jdbcTemplate.queryForObject(
                "SELECT download_count FROM tp_tool WHERE id = ?", Integer.class, id);
        assertEquals(2, cnt, "重复签发逐次涨量（V47 裁定②③）");
    }

    @Test
    void 下载zip_含契约json与DOC正文_EXECUTABLE仅契约json() {
        long authorId = SharedDbFixtureSweep.seedUser(jdbcTemplate, "tmt-z", "包装员");
        long docId = publishedTool(authorId, doc(null, "ZIP文档工具", "规范正文"));
        long execId = publishedTool(authorId, http(null, "ZIP执行工具"));

        ToolMarketDownloadView zip = toolMarketService.download(docId, "zip");
        storageKeys.add(zip.objectKey());
        assertTrue(zip.fileName().endsWith(".zip"), zip.fileName());
        String json = entryOf(zip.objectKey(), "tool.json");
        // Prefix match without the closing quote: the tracked name carries the
        // run token after the base name.
        assertTrue(json.contains("\"name\":\"ZIP文档工具"), json);
        assertTrue(json.contains("测试数据规范"), "契约含描述");
        assertNotNull(entryOf(zip.objectKey(), "body.md"), "DOC 包含正文条目");

        ToolMarketDownloadView execZip = toolMarketService.download(execId, "zip");
        storageKeys.add(execZip.objectKey());
        String execJson = entryOf(execZip.objectKey(), "tool.json");
        assertTrue(execJson.contains("\"execKind\":1"), "EXECUTABLE 契约含执行形态");
        assertTrue(execJson.contains("\"url\""), "exec_spec 原样入约（authRef 是引用键非机密）");

        ServiceException fmt = assertThrows(ServiceException.class,
                () -> toolMarketService.download(docId, "exe"));
        assertTrue(fmt.getMessage().contains("md"), fmt.getMessage());
    }

    @Test
    void 非published拒40001引导语含已发布_不存在404() {
        long authorId = SharedDbFixtureSweep.seedUser(jdbcTemplate, "tmt-np", "拒下载");
        ToolInfo draft = support.createTrackedTool(toolService, authorId, doc(null, "未发布工具", "x"));
        ServiceException forbidden = assertThrows(ServiceException.class,
                () -> toolMarketService.download(draft.id(), "md"));
        assertEquals(BizErrorCode.VALIDATION_ERROR, forbidden.getCode());
        assertTrue(forbidden.getMessage().contains("已发布"), forbidden.getMessage());
        assertThrows(ServiceException.class, () -> toolMarketService.download(999999L, "md"));
    }


    @Test
    void 导出zip换项目上传导入_生成工具自动进审批_字段往返一致() {
        long authorId = SharedDbFixtureSweep.seedUser(jdbcTemplate, "tmt-i", "导入者");
        long src = publishedTool(authorId, doc(null, "往返源工具", "往返正文"));
        long proj = support.createProject("RT1");
        ToolMarketDownloadView zip = toolMarketService.download(src, "zip");
        storageKeys.add(zip.objectKey());
        byte[] pack = storageRouter.systemProvider().get(zip.objectKey());

        ToolInfo imported = toolMarketService.importTool(authorId, proj, zip.fileName(), pack);
        support.tools().add(imported.id());
        assertEquals("待审批", imported.statusLabel(), "draft 即自动 submit（S2 链零复制）");
        assertEquals(1, imported.currentVersion());
        // The imported row carries the exported (token-scoped) name.
        assertTrue(imported.name().contains("往返源工具"), imported.name());
        assertEquals("取数", imported.category());
        assertEquals("测试数据规范", imported.description());
        String body = jdbcTemplate.queryForObject(
                "SELECT body FROM tp_tool WHERE id = ?", String.class, imported.id());
        assertEquals("往返正文", body);
    }

    @Test
    void IT_TOOL_05导入EXECUTABLE契约_三形态字段落库() {
        long authorId = SharedDbFixtureSweep.seedUser(jdbcTemplate, "tmt-ie", "执行导入");
        byte[] pack = zipBytes(Map.of("name", "导入HTTP工具", "type", 2, "category", "数据",
                "inputSchema", "{\"type\":\"object\"}", "execKind", 1,
                "execSpec", "{\"url\":\"http://localhost:9/s2\",\"authRef\":\"k2\"}",
                "timeoutMs", 45000));
        ToolInfo imported = toolMarketService.importTool(authorId, null, "e.zip", pack);
        support.tools().add(imported.id());
        assertEquals("可执行", imported.typeLabel());
        assertEquals("HTTP回调", imported.execKindLabel());
        assertEquals(45000, imported.timeoutMs());
        assertTrue(imported.execSpecJson().contains("k2"), "exec_spec 原样落库（引用键非机密）");
    }

    @Test
    void 导入重名40904_系统级桶冲突友好报错() {
        long authorId = SharedDbFixtureSweep.seedUser(jdbcTemplate, "tmt-dupe", "重名者");
        support.createTrackedTool(toolService, authorId, doc(null, "撞名工具", "x"));
        // The contract must carry the tracked row's full scoped name
        // (base name + run token) so the duplicate verdict fires on this run.
        byte[] pack = zipBytes(Map.of("name", "撞名工具 " + support.token(), "type", 1, "body", "y"));
        ServiceException dup = assertThrows(ServiceException.class, () -> toolMarketService.importTool(
                authorId, null, "d.zip", pack));
        assertEquals(BizErrorCode.RESOURCE_CONFLICT, dup.getCode());
        assertTrue(dup.getMessage().contains("撞名工具"), dup.getMessage());
    }


    @Test
    void IT_TOOL_05非zip与契约缺失各400人话_零落库() {
        long authorId = SharedDbFixtureSweep.seedUser(jdbcTemplate, "tmt-b", "坏文件");
        ServiceException notZip = assertThrows(ServiceException.class, () -> toolMarketService.importTool(
                authorId, null, "raw.zip", "# 不是 zip 的裸文本".getBytes(StandardCharsets.UTF_8)));
        assertTrue(notZip.getMessage().contains("zip"), notZip.getMessage());

        ServiceException noJson = assertThrows(ServiceException.class, () -> toolMarketService.importTool(
                authorId, null, "n.zip", ZipPackSupport.buildZip("readme.md", "没有契约文件")));
        assertTrue(noJson.getMessage().contains("tool.json"), noJson.getMessage());

        ServiceException noName = assertThrows(ServiceException.class, () -> toolMarketService.importTool(
                authorId, null, "nn.zip", zipBytes(Map.of("type", 1, "body", "x"))));
        assertTrue(noName.getMessage().contains("name"), noName.getMessage());

        ServiceException badType = assertThrows(ServiceException.class, () -> toolMarketService.importTool(
                authorId, null, "bt.zip", zipBytes(Map.of("name", "枚举外", "type", 9))));
        assertTrue(badType.getMessage().contains("文档类"), "报错含合法枚举: " + badType.getMessage());

        ServiceException docNoBody = assertThrows(ServiceException.class, () -> toolMarketService.importTool(
                authorId, null, "nb.zip", zipBytes(Map.of("name", "空正文", "type", 1))));
        assertTrue(docNoBody.getMessage().contains("正文"), docNoBody.getMessage());

        assertTrue(support.newToolIds().isEmpty(), "全部拒绝零落库");
    }

    @Test
    void IT_TOOL_05_zip炸弹_超1000项与超50MB各400人话_流式不落盘() {
        long authorId = SharedDbFixtureSweep.seedUser(jdbcTemplate, "tmt-bz", "炸弹");
        ByteArrayOutputStream entriesBos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(entriesBos, StandardCharsets.UTF_8)) {
            for (int i = 0; i < ZipPackSupport.ZIP_MAX_ENTRIES + 1; i++) {
                zos.putNextEntry(new ZipEntry("f" + i + ".json"));
                zos.write("{}".getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        ServiceException entryBomb = assertThrows(ServiceException.class, () -> toolMarketService.importTool(
                authorId, null, "entries.zip", entriesBos.toByteArray()));
        assertTrue(entryBomb.getMessage().contains("1000"), entryBomb.getMessage());

        ByteArrayOutputStream sizeBos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(sizeBos, StandardCharsets.UTF_8)) {
            zos.putNextEntry(new ZipEntry("big.json"));
            byte[] chunk = new byte[1024 * 1024];
            for (int i = 0; i < ZipPackSupport.ZIP_MAX_UNCOMPRESSED / chunk.length + 2; i++) {
                zos.write(chunk);
            }
            zos.closeEntry();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        ServiceException sizeBomb = assertThrows(ServiceException.class, () -> toolMarketService.importTool(
                authorId, null, "size.zip", sizeBos.toByteArray()));
        assertTrue(sizeBomb.getMessage().contains("50"), sizeBomb.getMessage());
        assertTrue(support.newToolIds().isEmpty(), "炸弹包零落库零解压残留（流式校验）");
    }


    
    private static byte[] zipBytes(java.util.Map<String, Object> contract) {
        try {
            return ZipPackSupport.buildZip("tool.json",
                    new com.fasterxml.jackson.databind.ObjectMapper()
                            .writeValueAsString(contract));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    
    private String entryOf(String objectKey, String entryName) {
        byte[] zipBytes = storageRouter.systemProvider().get(objectKey);
        try (ZipInputStream zis = new ZipInputStream(
                new java.io.ByteArrayInputStream(zipBytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().equals(entryName)) {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    zis.transferTo(bos);
                    return bos.toString(StandardCharsets.UTF_8);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return null;
    }
}
