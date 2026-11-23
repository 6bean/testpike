package com.sixbean.testpike.core.skill.domain.service;

import com.sixbean.testpike.common.approval.ApprovalStatus;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.executor.domain.component.StorageRouter;
import com.sixbean.testpike.core.skill.api.dto.SkillCreateInput;
import com.sixbean.testpike.core.skill.api.dto.SkillInfo;
import com.sixbean.testpike.core.skill.api.dto.SkillMarketDownloadView;
import com.sixbean.testpike.core.skill.api.dto.SkillMarketItem;
import com.sixbean.testpike.core.skill.api.dto.SkillMarketQuery;
import com.sixbean.testpike.core.skill.domain.component.SkillPackSupport;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import com.sixbean.testpike.core.support.SkillSuiteSupport;
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
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class SkillMarketServiceTest {

    @Autowired
    private SkillService skillService;

    @Autowired
    private SkillMarketService skillMarketService;

    @Autowired
    private StorageRouter storageRouter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private SkillSuiteSupport support;

    @BeforeEach
    void prepareMarketSuite() {
        support = new SkillSuiteSupport(jdbcTemplate, List.of("tp_tag_dict"));
        support.begin();
    }

    @AfterEach
    void sweepMarketFixture() {
        support.sweep();
    }

    @AfterEach
    void sweepUsersAndStorageObjects() {
        jdbcTemplate.update("DELETE FROM tp_user WHERE username LIKE 'mkt-%'");
        for (String key : support.storageKeys()) {
            try {
                storageRouter.systemProvider().delete(key);
            } catch (Exception ignore) {
            }
        }
        support.storageKeys().clear();
    }



    private long seedProject(String code) {
        return support.createProject(code);
    }

    
    private long publishedSkill(long operatorId, String name, String body) {
        return publishedSkill(operatorId, name, 1, body);
    }

    /** Skill create with the run token in the body plus R37 teardown tracking. */
    private SkillInfo createSkill(long operatorId, String name, int type, String body) {
        SkillInfo info = skillService.create(operatorId,
                new SkillCreateInput(null, name, type, body + " " + support.token(), null, null));
        support.skills().add(info.id());
        return info;
    }

    
    private long publishedSkill(long operatorId, String name, int type, String body) {
        SkillInfo created = createSkill(operatorId, name, type, body);
        skillService.submit(operatorId, created.id(), created.version());
        SkillInfo pending = skillService.get(created.id());
        skillService.approve(1L, created.id(), pending.version());
        return created.id();
    }

    private String validFrontmatter(String name, int type) {
        return "---\nname: " + name + "\ntype: " + type
                + "\nstages: [1, 2]\nscope: system\nversion: 1.0.0\n---\n\n# 正文\n\n规范内容。 " + support.token();
    }

    private static byte[] zipOf(String entryName, String content) {
        return SkillPackSupport.buildZip(entryName, content);
    }


    @Test
    void 市场列表只回published_三列聚合与作者display_name正确() {
        long authorId = SharedDbFixtureSweep.seedUser(jdbcTemplate, "mkt-author", "张三丰");
        long publishedId = publishedSkill(authorId, "用例检查规范", "检查步骤完整性");
        createSkill(authorId, "草稿技能", 2, "x");
        SkillInfo pending = createSkill(authorId, "待审批技能", 3, "x");
        skillService.submit(authorId, pending.id(), pending.version());

        long p1 = seedProject("MKT1");
        long p2 = seedProject("MKT2");
        jdbcTemplate.update("INSERT INTO tp_project_skill (project_id, skill_id) VALUES (?, ?)", p1, publishedId);
        jdbcTemplate.update("INSERT INTO tp_project_skill (project_id, skill_id) VALUES (?, ?)", p2, publishedId);
        jdbcTemplate.update("INSERT INTO tp_skill_usage (skill_id, stage) VALUES (?, 1)", publishedId);
        jdbcTemplate.update("INSERT INTO tp_skill_usage (skill_id, stage) VALUES (?, 2)", publishedId);
        jdbcTemplate.update("INSERT INTO tp_skill_usage (skill_id, stage, created_at) VALUES (?, 1, now() - interval '31 days')", publishedId);

        PageResult<SkillMarketItem> page = skillMarketService.marketList(new SkillMarketQuery(1, 20, support.token(), null, null, null, null));
        assertEquals(1, page.total(), "市场仅 published");
        SkillMarketItem item = page.list().get(0);
        assertEquals(publishedId, item.id());
        assertEquals("张三丰", item.authorName(), "作者=tp_user.display_name LEFT JOIN");
        assertEquals(0, item.downloadCount());
        assertEquals(2, item.refProjectCount(), "引用项目数=tp_project_skill 实时聚合");
        assertEquals(2, item.call30dCount(), "30 天调用=tp_skill_usage 窗内聚合（31 天外剔除）");
        assertEquals("已发布", item.statusLabel());
    }

    @Test
    void 市场筛选_keyword命中名称正文_type与tagId过滤() {
        long authorId = SharedDbFixtureSweep.seedUser(jdbcTemplate, "mkt-f", "过滤器");
        long a = publishedSkill(authorId, "支付回归检查", 1, "共享正文内容");
        long b = publishedSkill(authorId, "登录冒烟规范", 2, "别的正文");
        long proj = seedProject("TAGP");
        Long tagId = jdbcTemplate.queryForObject(
                "INSERT INTO tp_tag_dict (project_id, name, type) VALUES (?, '资金安全', 5) RETURNING id",
                Long.class, proj);
        jdbcTemplate.update("INSERT INTO tp_skill_tag (skill_id, tag_id) VALUES (?, ?)", a, tagId);

        assertEquals(1, skillMarketService.marketList(
                new SkillMarketQuery(1, 20, "支付", null, null, null, null)).total());
        assertEquals(1, skillMarketService.marketList(
                new SkillMarketQuery(1, 20, "共享正文", null, null, null, null)).total(), "keyword 命中正文");
        assertEquals(1, skillMarketService.marketList(
                new SkillMarketQuery(1, 20, support.token(), 2, null, null, null)).total(), "type=需求补齐(2) 仅剩登录冒烟");
        PageResult<SkillMarketItem> tagged = skillMarketService.marketList(
                new SkillMarketQuery(1, 20, null, null, tagId, null, null));
        assertEquals(1, tagged.total());
        assertEquals(a, tagged.list().get(0).id());
        assertEquals(List.of("资金安全"), tagged.list().get(0).tags(), "标签列展示不截断");
    }

    @Test
    void 市场排序白名单三列可排_非白名单400人话() {
        long authorId = SharedDbFixtureSweep.seedUser(jdbcTemplate, "mkt-s", "排序员");
        long small = publishedSkill(authorId, "下载少", "x");
        long big = publishedSkill(authorId, "下载多", "x");
        jdbcTemplate.update("UPDATE tp_skill SET download_count = 9 WHERE id = ?", big);
        jdbcTemplate.update("UPDATE tp_skill SET download_count = 1 WHERE id = ?", small);

        List<SkillMarketItem> desc = skillMarketService.marketList(
                new SkillMarketQuery(1, 20, support.token(), null, null, "downloadCount", "desc")).list();
        assertEquals(List.of(big, small), desc.stream().map(SkillMarketItem::id).toList());
        skillMarketService.marketList(new SkillMarketQuery(1, 20, null, null, null, "refProjectCount", "asc"));
        skillMarketService.marketList(new SkillMarketQuery(1, 20, null, null, null, "call30d", "asc"));
        ServiceException bad = assertThrows(ServiceException.class, () -> skillMarketService.marketList(
                new SkillMarketQuery(1, 20, null, null, null, "body; DROP TABLE tp_skill", "desc")));
        assertEquals(BizErrorCode.VALIDATION_ERROR, bad.getCode());
        assertTrue(bad.getMessage().contains("downloadCount"), "报错含允许清单: " + bad.getMessage());
    }


    @Test
    void 下载md_预签名URL可取_contentType正确_签发即download_count涨() {
        long authorId = SharedDbFixtureSweep.seedUser(jdbcTemplate, "mkt-d", "下载作者");
        long id = publishedSkill(authorId, "下载涨量技能", "正文ABC");

        SkillMarketDownloadView view = skillMarketService.download(id, "md");
        support.storageKeys().add(view.objectKey());
        assertNotNull(view.url(), "presign 带签 URL");
        assertTrue(view.url().contains("http"), view.url());
        assertEquals(id, view.skillId());
        Integer cnt = jdbcTemplate.queryForObject(
                "SELECT download_count FROM tp_skill WHERE id = ?", Integer.class, id);
        assertEquals(1, cnt);
        skillMarketService.download(id, "md");
        cnt = jdbcTemplate.queryForObject("SELECT download_count FROM tp_skill WHERE id = ?", Integer.class, id);
        assertEquals(2, cnt, "重复签发逐次涨量");
    }

    @Test
    void 下载zip包内含md_非published拒40001_格式枚举外400() {
        long authorId = SharedDbFixtureSweep.seedUser(jdbcTemplate, "mkt-z", "包装员");
        long id = publishedSkill(authorId, "ZIP下载技能", "正文");
        SkillMarketDownloadView zip = skillMarketService.download(id, "zip");
        support.storageKeys().add(zip.objectKey());
        assertTrue(zip.fileName().endsWith(".zip"), zip.fileName());

        SkillInfo draft = createSkill(authorId, "未发布", 1, "x");
        ServiceException forbidden = assertThrows(ServiceException.class,
                () -> skillMarketService.download(draft.id(), "md"));
        assertEquals(BizErrorCode.VALIDATION_ERROR, forbidden.getCode());
        assertTrue(forbidden.getMessage().contains("已发布"), forbidden.getMessage());
        ServiceException fmt = assertThrows(ServiceException.class,
                () -> skillMarketService.download(id, "exe"));
        assertTrue(fmt.getMessage().contains("md"), fmt.getMessage());
        assertThrows(ServiceException.class, () -> skillMarketService.download(999999L, "md"));
    }


    @Test
    void 导入合法md_生成SKILL自动进审批_frontmatter五键落库() {
        long authorId = SharedDbFixtureSweep.seedUser(jdbcTemplate, "mkt-i", "导入人");
        SkillInfo imported = skillMarketService.importSkill(authorId, null,
                "支付检查.md", validFrontmatter("导入的技能", 1).getBytes(StandardCharsets.UTF_8));
        support.skills().add(imported.id());
        assertEquals("导入的技能", imported.name());
        assertEquals(ApprovalStatus.PENDING_APPROVAL.code(), imported.status(), "draft 即进审批=pending");
        assertNotNull(imported.frontmatterJson());
        assertTrue(imported.frontmatterJson().contains("stages"), "五键完整落 frontmatter jsonb");
        assertTrue(imported.bodyPreview().contains("规范内容"),
                "正文预览=frontmatter 与标题之后的首个散文段（列表禁回裸 markdown 与标题壳）");
    }

    @Test
    void 导入zip包_找md条目_非zip损坏与无md条目400人话() {
        long authorId = SharedDbFixtureSweep.seedUser(jdbcTemplate, "mkt-zip", "包导入");
        byte[] good = zipOf("skill.md", validFrontmatter("ZIP导入技能", 2));
        SkillInfo imported = skillMarketService.importSkill(authorId, null, "pack.zip", good);
        support.skills().add(imported.id());
        assertEquals("ZIP导入技能", imported.name());

        ServiceException broken = assertThrows(ServiceException.class, () -> skillMarketService.importSkill(
                authorId, null, "fake.zip", "这不是zip".getBytes(StandardCharsets.UTF_8)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, broken.getCode());
        ServiceException noMd = assertThrows(ServiceException.class, () -> skillMarketService.importSkill(
                authorId, null, "nomd.zip", zipOf("readme.txt", "hello")));
        assertTrue(noMd.getMessage().contains(".md"), noMd.getMessage());
    }

    @Test
    void IT_SKL_04缺frontmatter与非法type_YAML畸形各400人话_零落库() {
        long authorId = SharedDbFixtureSweep.seedUser(jdbcTemplate, "mkt-b", "坏文件");
        ServiceException noFm = assertThrows(ServiceException.class, () -> skillMarketService.importSkill(
                authorId, null, "raw.md", "# 没有 frontmatter 的正文".getBytes(StandardCharsets.UTF_8)));
        assertTrue(noFm.getMessage().contains("frontmatter"), noFm.getMessage());

        ServiceException badType = assertThrows(ServiceException.class, () -> skillMarketService.importSkill(
                authorId, null, "t.md", validFrontmatter("类型六技能", 6).getBytes(StandardCharsets.UTF_8)));
        assertTrue(badType.getMessage().contains("用例检查"), "报错含合法枚举: " + badType.getMessage());

        ServiceException badYaml = assertThrows(ServiceException.class, () -> skillMarketService.importSkill(
                authorId, null, "y.md", "---\nname: [未闭合\n---\n正文".getBytes(StandardCharsets.UTF_8)));
        assertTrue(badYaml.getMessage().contains("frontmatter"), badYaml.getMessage());

        ServiceException noName = assertThrows(ServiceException.class, () -> skillMarketService.importSkill(
                authorId, null, "n.md", "---\ntype: 1\n---\n正文".getBytes(StandardCharsets.UTF_8)));
        assertTrue(noName.getMessage().contains("name"), noName.getMessage());

        assertEquals(0, support.countSkills() - support.skillsBefore(), "全部拒绝零落库");
    }

    @Test
    void IT_SKL_04_zip炸弹_超1000项与超50MB各400人话_流式不落盘() throws Exception {
        long authorId = SharedDbFixtureSweep.seedUser(jdbcTemplate, "mkt-bz", "炸弹");
        ByteArrayOutputStream entriesBos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(entriesBos, StandardCharsets.UTF_8)) {
            for (int i = 0; i < SkillPackSupport.ZIP_MAX_ENTRIES + 1; i++) {
                zos.putNextEntry(new ZipEntry("f" + i + ".md"));
                zos.write("x".getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        ServiceException entryBomb = assertThrows(ServiceException.class, () -> skillMarketService.importSkill(
                authorId, null, "entries.zip", entriesBos.toByteArray()));
        assertTrue(entryBomb.getMessage().contains("1000"), entryBomb.getMessage());

        ByteArrayOutputStream sizeBos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(sizeBos, StandardCharsets.UTF_8)) {
            zos.putNextEntry(new ZipEntry("big.md"));
            byte[] chunk = new byte[1024 * 1024];
            for (int i = 0; i < SkillPackSupport.ZIP_MAX_UNCOMPRESSED / chunk.length + 2; i++) {
                zos.write(chunk);
            }
            zos.closeEntry();
        }
        ServiceException sizeBomb = assertThrows(ServiceException.class, () -> skillMarketService.importSkill(
                authorId, null, "size.zip", sizeBos.toByteArray()));
        assertTrue(sizeBomb.getMessage().contains("50"), sizeBomb.getMessage());
        assertEquals(0, support.countSkills() - support.skillsBefore(), "炸弹包零落库零解压残留（流式校验）");
    }

    @Test
    void 导入重名40904_系统级桶冲突友好报错() {
        long authorId = SharedDbFixtureSweep.seedUser(jdbcTemplate, "mkt-dupe", "重名者");
        createSkill(authorId, "撞名技能", 1, "x");
        ServiceException dup = assertThrows(ServiceException.class, () -> skillMarketService.importSkill(
                authorId, null, "d.md", validFrontmatter("撞名技能", 1).getBytes(StandardCharsets.UTF_8)));
        assertEquals(BizErrorCode.RESOURCE_CONFLICT, dup.getCode());
        assertTrue(dup.getMessage().contains("撞名技能"), dup.getMessage());
    }

}
