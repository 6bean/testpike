package com.sixbean.testpike.core.common.domain.component;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Preview contract for list/detail view surfaces (AR stage-4 close-out).
 *
 * <p>The list preview is the only body text a user ever sees without opening
 * the editor, so it must never leak markup noise or internal note markers
 * (template stage tags, hot-config key families, contract anchors). Preview =
 * the first prose paragraph of the document: heading paragraphs (the official
 * templates carry an internal note in the title parentheses) are skipped,
 * inline markdown emphasis and backticks are stripped, and parenthesized
 * internal annotations are dropped. Plain documents without any heading keep
 * their full text and the 500-char truncation with the ellipsis marker.
 */
class ViewFormatsPreviewTest {

    /** Verbatim head of the factory script-writing template (tp_skill id 5951). */
    private static final String TEMPLATE_WITH_STAGE_NOTE = """
            \n# 脚本开发规范（官方模板 · 脚本开发类，stage2 注入）

            将用例转换为动作 JSON v2 脚本时遵循（限额具体数值以 ai.script.* 热更注入行为准，勿凭记忆写死）：

            1. **结构**：顶层必含 version=v2、caseId、env（旁路族见 04 契约 B11）。
            2. **定位**：优先 id/name/css 稳定选择器。
            """;

    /** Verbatim head of the factory case-review template (tp_skill id 5950). */
    private static final String TEMPLATE_WITHOUT_STAGE_NOTE = """
            # 用例质量检查规范（官方模板 · 用例检查类）

            生成/评审测试用例时遵循以下检查项，逐条满足后再输出：

            1. **标题**：动宾结构、可区分。
            """;

    @Test
    void nullBodyPassesThrough() {
        assertNull(ViewFormats.preview(null));
    }

    @Test
    void headingParagraphWithInternalNoteIsSkippedAndFirstProseLineWins() {
        String preview = ViewFormats.preview(TEMPLATE_WITH_STAGE_NOTE);
        assertEquals("将用例转换为动作 JSON v2 脚本时遵循：", preview,
                "preview must be the first prose sentence with the parenthesized internal note dropped");
    }

    @Test
    void previewNeverLeaksMarkupOrBookkeepingMarkers() {
        for (String preview : new String[]{
                ViewFormats.preview(TEMPLATE_WITH_STAGE_NOTE),
                ViewFormats.preview(TEMPLATE_WITHOUT_STAGE_NOTE)}) {
            for (String marker : new String[]{"**", "`", "stage1", "stage2", "/F2", "ai.script", "契约"}) {
                assertFalse(preview.contains(marker),
                        "preview leaked internal marker '" + marker + "': " + preview);
            }
        }
    }

    @Test
    void proseParagraphOfTemplateWithoutStageNoteIsExtracted() {
        assertEquals("生成/评审测试用例时遵循以下检查项，逐条满足后再输出：",
                ViewFormats.preview(TEMPLATE_WITHOUT_STAGE_NOTE));
    }

    @Test
    void plainParagraphWithoutBlankLinesKeepsTruncation() {
        // Same fixture shape as the frozen detail-vs-list contract: a single
        // long paragraph with no blank lines and no markdown must still be
        // truncated at PREVIEW_MAX with the ellipsis.
        String longBody = "行内容".repeat(300);
        String preview = ViewFormats.preview(longBody);
        assertEquals(ViewFormats.PREVIEW_MAX + 1, preview.length(),
                "truncated preview = 500 chars + ellipsis");
        assertTrue(preview.endsWith("…"));
    }

    @Test
    void shortPlainBodyPassesThroughUnchanged() {
        assertEquals("旧版正文", ViewFormats.preview("旧版正文"));
    }

    @Test
    void inlineEmphasisAndBackticksAreStrippedFromProse() {
        assertEquals("先拆分用例而非硬堆步骤。",
                ViewFormats.preview("先**拆分**用例而非`硬堆`步骤。"));
    }
}
