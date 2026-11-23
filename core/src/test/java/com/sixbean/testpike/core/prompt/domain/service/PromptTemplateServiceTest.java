package com.sixbean.testpike.core.prompt.domain.service;

import com.sixbean.testpike.common.exception.ValidationException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.behavior.api.dto.GoldenDriftPair;
import com.sixbean.testpike.core.behavior.domain.component.GoldenDriftGate;
import com.sixbean.testpike.core.prompt.api.dto.PromptTemplateFamilyItem;
import com.sixbean.testpike.core.prompt.api.dto.PromptTemplateItem;
import com.sixbean.testpike.core.prompt.api.dto.PromptTemplateSaveInput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class PromptTemplateServiceTest {

    private static final String FIXTURE_LIKE = "t253.%";

    @Autowired
    private PromptTemplateService promptTemplateService;

    @Autowired
    private GoldenDriftGate goldenDriftGate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void cleanFixture() {
        jdbcTemplate.update("DELETE FROM tp_ai_golden_drift_verdict WHERE template_name LIKE ?", FIXTURE_LIKE);
        jdbcTemplate.update("DELETE FROM tp_prompt_template WHERE name LIKE ?", FIXTURE_LIKE);
    }

    /**
 * Golden replay admission : promotion to full release
 * now requires a passing drift verdict, so fixture promotes record one.
 */
    private void recordBenignVerdict(String name, String domain, long candidateId, long controlId) {
        goldenDriftGate.recordComparison(domain, name, candidateId, controlId,
                "t253-golden-pack", 1, "f".repeat(64),
                List.of(new GoldenDriftPair("t253-s1", "same", "same")), "fixture benign replay");
    }

    private long countRows(String name, String domain) {
        Long c = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_prompt_template WHERE name = ? AND domain = ?",
                Long.class, name, domain);
        return c == null ? 0 : c;
    }

    @Test
    void 首次保存_版本1_默认灰度_字段透传() {
        PromptTemplateItem saved = promptTemplateService.save(
                new PromptTemplateSaveInput("t253.first", "用例生成", "你好 {{requirement}}", 9001L));

        assertNotNull(saved.id());
        assertEquals("t253.first", saved.name());
        assertEquals("用例生成", saved.domain());
        assertEquals(1, saved.version());
        assertEquals(1, saved.status(), "新版本默认灰度");
        assertEquals("你好 {{requirement}}", saved.content());
        assertEquals(9001L, saved.createdBy());
        assertNull(saved.rollbackTo(), "非回滚产物来源为空");
        assertNotNull(saved.createdAt());
    }

    @Test
    void 修改即新版本_族内递增_旧行原文不动() {
        PromptTemplateItem v1 = promptTemplateService.save(
                new PromptTemplateSaveInput("t253.bump", "脚本生成", "旧骨架", 1L));
        PromptTemplateItem v2 = promptTemplateService.save(
                new PromptTemplateSaveInput("t253.bump", "脚本生成", "新骨架", 2L));

        assertEquals(1, v1.version());
        assertEquals(2, v2.version(), "同 name+domain 再存=version 递增");
        assertEquals(2, countRows("t253.bump", "脚本生成"), "两条都是独立行（钉住旧版本可读的前提）");

        PromptTemplateItem reread = promptTemplateService.detail(v1.id());
        assertEquals("旧骨架", reread.content(), "旧版本行内容不被覆盖（任务钉住不回拽）");

        PromptTemplateItem otherDomain = promptTemplateService.save(
                new PromptTemplateSaveInput("t253.bump", "失败归因", "归因骨架", 3L));
        assertEquals(1, otherDomain.version(), "同 name 异 domain 从 1 起算");
    }

    @Test
    void 回滚_旧行内容复制为新版本_rollback_to记来源() {
        PromptTemplateItem v1 = promptTemplateService.save(
                new PromptTemplateSaveInput("t253.rb", "用例生成", "V1内容", 1L));
        promptTemplateService.save(new PromptTemplateSaveInput("t253.rb", "用例生成", "V2坏了", 1L));

        PromptTemplateItem rolled = promptTemplateService.rollback(v1.id(), 77L);

        assertEquals(3, rolled.version(), "回滚产物继续族内递增");
        assertEquals("V1内容", rolled.content(), "旧内容复制为新版本行");
        assertEquals(v1.id(), rolled.rollbackTo(), "记回滚来源行 id");
        assertEquals(1, rolled.status(), "回滚产物同样默认灰度");
        assertEquals(3, countRows("t253.rb", "用例生成"));
    }

    @Test
    void 回滚目标不存在_人话点名() {
        ValidationException ex = assertThrows(ValidationException.class,
                () -> promptTemplateService.rollback(999999901L, 1L));
        assertTrue(ex.getMessage().contains("不存在"), "人话点名：999999901 不存在案：" + ex.getMessage());
    }

    @Test
    void 必填缺失_40001人话点名() {
        ValidationException blankName = assertThrows(ValidationException.class,
                () -> promptTemplateService.save(new PromptTemplateSaveInput("  ", "d", "c", 1L)));
        assertTrue(blankName.getMessage().contains("模板名"), "点名模板名：" + blankName.getMessage());

        ValidationException blankDomain = assertThrows(ValidationException.class,
                () -> promptTemplateService.save(new PromptTemplateSaveInput("t253.x", null, "c", 1L)));
        assertTrue(blankDomain.getMessage().contains("业务域"), "点名业务域：" + blankDomain.getMessage());

        ValidationException blankContent = assertThrows(ValidationException.class,
                () -> promptTemplateService.save(new PromptTemplateSaveInput("t253.x", "d", "   ", 1L)));
        assertTrue(blankContent.getMessage().contains("模板内容"), "点名模板内容：" + blankContent.getMessage());

        assertEquals(0, countRows("t253.x", "d"));
    }

    @Test
    void 详情_列表按族查最新版本_分页倒序() {
        promptTemplateService.save(new PromptTemplateSaveInput("t253.list", "d1", "a", 1L));
        promptTemplateService.save(new PromptTemplateSaveInput("t253.list", "d1", "b", 1L));
        promptTemplateService.save(new PromptTemplateSaveInput("t253.list2", "d1", "c", 1L));

        List<PromptTemplateItem> fam = promptTemplateService.listVersions("t253.list", "d1");
        assertEquals(2, fam.size());
        assertEquals(2, fam.get(0).version(), "族内版本倒序，最新在前");

        PromptTemplateItem latest = promptTemplateService.latest("t253.list", "d1");
        assertEquals("b", latest.content());

        assertTrue(promptTemplateService.listVersions("t253.none", "d1").isEmpty());
        assertNull(promptTemplateService.latest("t253.none", "d1"));
    }

    @Test
    void 按状态族内最新_灰度与全量各取各链路() {
        promptTemplateService.save(new PromptTemplateSaveInput("t253.bk", "用例生成", "灰1", 1L));
        promptTemplateService.save(new PromptTemplateSaveInput("t253.bk", "用例生成", "灰2", 1L));
        List<PromptTemplateItem> all = promptTemplateService.listVersions("t253.bk", "用例生成");
        recordBenignVerdict("t253.bk", "用例生成", all.get(0).id(), all.get(1).id());
        promptTemplateService.promote(all.get(0).id());

        PromptTemplateItem gray = promptTemplateService.latestByStatus("t253.bk", "用例生成", 1);
        PromptTemplateItem full = promptTemplateService.latestByStatus("t253.bk", "用例生成", 2);
        assertNotNull(gray, "灰度桶读路径须出灰度族最新行");
        assertEquals("灰1", gray.content(), "晋升后剩第一版=灰度族最新");
        assertEquals(1, gray.status());
        assertNotNull(full, "全量桶读路径须出全量族最新行");
        assertEquals("灰2", full.content(), "晋升行=全量族最新");
        assertEquals(2, full.status());

        assertNull(promptTemplateService.latestByStatus("t253.none", "用例生成", 1));
        assertNull(promptTemplateService.latestByStatus("t253.bk", "用例生成", 3));

        ValidationException bad = assertThrows(ValidationException.class,
                () -> promptTemplateService.latestByStatus("t253.bk", "用例生成", 9));
        assertTrue(bad.getMessage().contains("状态"), "点名状态非法：" + bad.getMessage());
    }

    @Test
    void 晋升全量_灰度行升状态2_旧全量行落历史() {
        PromptTemplateItem v0 = promptTemplateService.save(
                new PromptTemplateSaveInput("t253.pm", "用例生成", "初版", 1L));
        PromptTemplateItem g1 = promptTemplateService.save(
                new PromptTemplateSaveInput("t253.pm", "用例生成", "旧全量", 1L));
        recordBenignVerdict("t253.pm", "用例生成", g1.id(), v0.id());
        promptTemplateService.promote(g1.id());
        PromptTemplateItem g2 = promptTemplateService.save(
                new PromptTemplateSaveInput("t253.pm", "用例生成", "新灰度", 1L));
        recordBenignVerdict("t253.pm", "用例生成", g2.id(), g1.id());

        PromptTemplateItem promoted = promptTemplateService.promote(g2.id());

        assertEquals(2, promoted.status(), "晋升=灰度→全量");
        assertEquals(3, promptTemplateService.detail(g1.id()).status(),
                "族内同时至多一个全量行，旧全量落历史");
        assertThrows(ValidationException.class,
                () -> promptTemplateService.promote(promptTemplateService.detail(g1.id()).id()),
                "历史行不可晋升（只有灰度行可升）");
        assertThrows(ValidationException.class,
                () -> promptTemplateService.promote(999999902L), "不存在=40001 人话");
    }

    @Test
    void 迁移落库实证_钉住列与灰度键在位() {
        Integer col = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.columns"
                        + " WHERE table_name = 'tp_ai_task' AND column_name = 'template_version_id'",
                Integer.class);
        assertEquals(1, col, "tp_ai_task.template_version_id 钉住列未落库（V96 未执行）");

        Integer tbl = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_name = 'tp_prompt_template'",
                Integer.class);
        assertEquals(1, tbl, "tp_prompt_template 表未落库（V96 未执行）");

        String pct = jdbcTemplate.queryForObject(
                "SELECT config_value FROM tp_sys_config WHERE config_key = 'prompt.gen.traffic-pct'",
                String.class);
        assertEquals("0", pct, "灰度流量键种子缺失或非默认 0（存量零变化被破坏）");
    }

    @Test
    void 族清单_每族一行最新版本_域过滤与出厂族可见() {
        promptTemplateService.save(new PromptTemplateSaveInput("t253.fam", "d1", "v1", 1L));
        promptTemplateService.save(new PromptTemplateSaveInput("t253.fam", "d1", "v2坏了", 1L));
        promptTemplateService.save(new PromptTemplateSaveInput("t253.fam2", "d2", "only", 1L));

        List<PromptTemplateFamilyItem> all = promptTemplateService.listFamilies(null);
        PromptTemplateFamilyItem fam = all.stream()
                .filter(f -> "t253.fam".equals(f.name()) && "d1".equals(f.domain()))
                .findFirst().orElseThrow();
        assertEquals(2, fam.latestVersion(), "族清单行=族内最新版本");
        assertEquals(2, fam.versionCount(), "版本数对账=两次保存各成一行");
        PromptTemplateItem latest = promptTemplateService.latest("t253.fam", "d1");
        assertEquals(latest.id(), fam.latestId(), "latestId 与 latest 读到的行一致");
        assertEquals(latest.status(), fam.latestStatus());
        assertNotNull(fam.updatedAt());

        List<PromptTemplateFamilyItem> onlyD2 = promptTemplateService.listFamilies(" d2 ");
        assertEquals(1, onlyD2.size(), "域过滤=精确按 domain 值，夹具 d2 只有 fam2 一族");
        assertEquals("t253.fam2", onlyD2.get(0).name());

        assertTrue(all.stream().anyMatch(f -> "case-gen-prompt".equals(f.name())),
                "出厂模板族必须出现在族清单");
        assertTrue(all.stream().anyMatch(f -> "review-prompt".equals(f.name())
                && "评审".equals(f.domain())));
    }
}
