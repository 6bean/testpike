package com.sixbean.testpike.aiservice.gen.domain.component;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * segment A: mechanical rule-item extraction over the anchored
 * original-text block set. Table-row shape reconciles per column (field /
 * input type / required / constraint); prose lines only produce a candidate
 * The extractor is a pure function: no DB, no LLM, no config reads.
 */
class RuleItemExtractorTest {

    private final RuleItemExtractor extractor = new RuleItemExtractor();

    @Test
    void extractsTableRowsWithStandardHeaderColumns() {
        String text = """
                主营品牌资料新增表单规则：
                | 字段 | 输入类型 | 必填 | 限制 |
                | --- | --- | --- | --- |
                | 品牌名称 | 文本框 | 是 | 不超过50字符 |
                | 品牌Logo | 图片上传 | 否 | 大小不超过2MB |
                | 所属集团 | 下拉选择 | 是 | 取值来自集团档案 |
                """;
        List<RuleItem> items = extractor.extract(List.of(new RuleSourceBlock(11L, text)));

        List<RuleItem> tableRows = items.stream()
                .filter(i -> i.kind() == RuleItemKind.TABLE_ROW)
                .toList();
        assertEquals(3, tableRows.size(), "separator row must not count as an item");
        RuleItem first = tableRows.get(0);
        assertEquals("品牌名称", first.field());
        assertEquals("文本框", first.inputType());
        assertEquals("是", first.required());
        assertEquals("不超过50字符", first.constraint());
        assertEquals(11L, first.chunkId(), "items keep block attribution for the ledger");
    }

    @Test
    void mapsHeaderSynonymsRegardlessOfColumnOrder() {
        String text = """
                | 校验规则 | 是否必填 | 名称 | 控件类型 |
                | --- | --- | --- | --- |
                | 仅允许中文与字母 | 必填 | 品牌简称 | 输入框 |
                """;
        List<RuleItem> items = extractor.extract(List.of(new RuleSourceBlock(12L, text)));
        List<RuleItem> tableRows = items.stream()
                .filter(i -> i.kind() == RuleItemKind.TABLE_ROW)
                .toList();
        assertEquals(1, tableRows.size());
        RuleItem item = tableRows.get(0);
        assertEquals("品牌简称", item.field());
        assertEquals("输入框", item.inputType());
        assertEquals("必填", item.required());
        assertEquals("仅允许中文与字母", item.constraint());
    }

    @Test
    void skipsTableRowsWithoutAFieldName() {
        String text = """
                | 字段 | 输入类型 | 必填 | 限制 |
                | --- | --- | --- | --- |
                |  | 文本框 | 是 | 不超过50字符 |
                | 联系人 | 文本框 | 是 | 不超过20字符 |
                """;
        List<RuleItem> items = extractor.extract(List.of(new RuleSourceBlock(13L, text)));
        List<RuleItem> tableRows = items.stream()
                .filter(i -> i.kind() == RuleItemKind.TABLE_ROW)
                .toList();
        assertEquals(1, tableRows.size(), "a row without a field name carries no rule");
        assertEquals("联系人", tableRows.get(0).field());
    }

    @Test
    void proseSentencesBecomeCandidatesNeverTableRows() {
        String text = """
                提交前必须完成短信验证。
                品牌图片不得超过2MB，格式仅允许 jpg 或 png。
                该页面在系统首页的导航栏可以进入。
                """;
        List<RuleItem> items = extractor.extract(List.of(new RuleSourceBlock(14L, text)));

        List<RuleItem> candidates = items.stream()
                .filter(i -> i.kind() == RuleItemKind.PROSE_CANDIDATE)
                .toList();
        assertEquals(2, candidates.size(), "narrative without rule wording stays out of the ledger");
        assertTrue(candidates.get(0).sourceText().contains("短信验证"));
        assertTrue(candidates.get(1).sourceText().contains("2MB"));
        // Prose candidates carry no machine verdict fields.
        assertNull(candidates.get(0).field());
        assertTrue(items.stream().noneMatch(i -> i.kind() == RuleItemKind.TABLE_ROW));
    }

    @Test
    void failsSoftOnEmptyOrBlankInput() {
        assertTrue(extractor.extract(List.of()).isEmpty());
        assertTrue(extractor.extract(null).isEmpty());
        assertTrue(extractor.extract(List.of(new RuleSourceBlock(15L, "   "))).isEmpty());
    }

    @Test
    void mergesItemsAcrossBlocksWithPerBlockAttribution() {
        String tableA = """
                | 字段 | 必填 |
                | --- | --- |
                | 品牌名称 | 是 |
                """;
        String tableB = """
                | 字段 | 必填 | 限制 |
                | --- | --- | --- |
                | 品牌编号 | 是 | 唯一 |
                """;
        List<RuleItem> items = extractor.extract(List.of(
                new RuleSourceBlock(21L, tableA),
                new RuleSourceBlock(22L, tableB)));
        List<RuleItem> tableRows = items.stream()
                .filter(i -> i.kind() == RuleItemKind.TABLE_ROW)
                .toList();
        assertEquals(2, tableRows.size());
        assertEquals(21L, tableRows.get(0).chunkId());
        assertEquals(22L, tableRows.get(1).chunkId());
        assertNull(tableRows.get(0).constraint(), "missing column stays null, never invented");
    }

    @Test
    void nonRulePipeTablesAreNotMistakenForFormRules() {
        String text = """
                | 订单号 | 金额 | 状态 |
                | --- | --- | --- |
                | A-001 | 12.5 | PAID |
                """;
        List<RuleItem> items = extractor.extract(List.of(new RuleSourceBlock(23L, text)));
        assertTrue(items.stream().noneMatch(i -> i.kind() == RuleItemKind.TABLE_ROW),
                "a table without any rule header column must not feed the ledger");
    }
}
