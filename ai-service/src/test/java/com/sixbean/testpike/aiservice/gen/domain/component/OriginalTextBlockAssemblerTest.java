package com.sixbean.testpike.aiservice.gen.domain.component;

import com.sixbean.testpike.aiservice.support.GenTestFixtures;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BA batch the original-text block assembler pulls the hit
 * chunk plus its ±1 neighbours out of the document chunk list, renders one
 * isolated block and clamps it to the caller-supplied character budget.
 * Covers: neighbour window selection in ascending order, anchor ids that
 * point at the same row being emitted once, evidence-excerpt fallback when
 * no id resolves, the empty verdicts (no anchor / no chunks / unknown
 * documentId) that keep the generation prompt byte-for-byte unchanged, the
 * budget clamp with an honest truncation marker, and verbatim excerpts
 * being emitted once.
 */
class OriginalTextBlockAssemblerTest {

    private final OriginalTextBlockAssembler assembler = new OriginalTextBlockAssembler();

    private static List<Map<String, Object>> chunks() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(GenTestFixtures.chunkRow(11L, 1, "2.1 登录", "第一块正文内容"));
        rows.add(GenTestFixtures.chunkRow(12L, 2, "2.1 登录", "第二块正文内容"));
        rows.add(GenTestFixtures.chunkRow(13L, 3, "2.2 首页", "第三块正文内容"));
        rows.add(GenTestFixtures.chunkRow(14L, 4, "2.2 首页", "第四块正文内容"));
        return rows;
    }

    @Test
    void hitChunkPullsAdjacentNeighboursInAscendingOrder() {
        OriginalTextBlockAssembler.Anchor anchor =
                new OriginalTextBlockAssembler.Anchor(7L, List.of(12L), null);
        String block = assembler.assemble(anchor, chunks(), 10000);
        assertTrue(block.contains("第一块正文内容"), "previous neighbour must be present");
        assertTrue(block.contains("第二块正文内容"), "hit chunk must be present");
        assertTrue(block.contains("第三块正文内容"), "next neighbour must be present");
        assertFalse(block.contains("第四块正文内容"), "window stops at ±1");
        int prev = block.indexOf("第一块正文内容");
        int hit = block.indexOf("第二块正文内容");
        int next = block.indexOf("第三块正文内容");
        assertTrue(prev < hit && hit < next, "emission follows ascending seq");
        assertTrue(block.startsWith("<original_text>"), "isolated open marker");
        assertTrue(block.endsWith("</original_text>"), "isolated close marker");
    }

    @Test
    void duplicateAnchorIdsEmitOneCopy() {
        OriginalTextBlockAssembler.Anchor anchor =
                new OriginalTextBlockAssembler.Anchor(7L, List.of(12L, 12L), null);
        String block = assembler.assemble(anchor, chunks(), 10000);
        assertEquals(1, occurrences(block, "第二块正文内容"), "same row anchored twice is emitted once");
    }

    @Test
    void evidenceFallbackWhenNoIdResolves() {
        OriginalTextBlockAssembler.Anchor anchor =
                new OriginalTextBlockAssembler.Anchor(7L, List.of(), "第二块 正文\n内容");
        String block = assembler.assemble(anchor, chunks(), 10000);
        assertTrue(block.contains("第二块正文内容"), "whitespace-normalised excerpt finds its chunk");
        assertEquals(1, occurrences(block, "第二块正文内容"));
    }

    @Test
    void emptyVerdictsProduceNoBlock() {
        assertEquals("", assembler.assemble(null, chunks(), 10000), "no anchor = no block");
        assertEquals("", assembler.assemble(
                new OriginalTextBlockAssembler.Anchor(null, List.of(12L), "正文"), chunks(), 10000),
                "anchor without a document cannot be pulled");
        assertEquals("", assembler.assemble(
                new OriginalTextBlockAssembler.Anchor(7L, List.of(12L), null), List.of(), 10000),
                "empty chunk list = no block");
        assertEquals("", assembler.assemble(
                new OriginalTextBlockAssembler.Anchor(7L, List.of(999L), null), chunks(), 10000),
                "chunk ids that no longer resolve = stale anchor, no block");
        assertEquals("", assembler.assemble(
                new OriginalTextBlockAssembler.Anchor(7L, List.of(), null), chunks(), 10000),
                "no ids and no excerpt = honest empty");
        assertEquals("", assembler.assemble(
                new OriginalTextBlockAssembler.Anchor(7L, List.of(11L), null), chunks(), 0),
                "a non-positive budget injects nothing rather than half a block");
    }

    @Test
    void budgetClampKeepsHeadAndMarksTruncation() {
        List<Map<String, Object>> wide = new ArrayList<>();
        wide.add(GenTestFixtures.chunkRow(1L, 1, "章", "A".repeat(500)));
        wide.add(GenTestFixtures.chunkRow(2L, 2, "章", "B".repeat(500)));
        String block = assembler.assemble(
                new OriginalTextBlockAssembler.Anchor(7L, List.of(1L), null), wide, 120);
        String body = block.substring("<original_text>".length(),
                block.length() - "</original_text>".length());
        assertTrue(body.length() <= 120 + "（内容已截断）".length() + 64,
                "clamped body stays within the budget plus the honest marker: " + body.length());
        assertTrue(block.contains("（内容已截断）"), "truncation must be visible, never silent");
        assertTrue(block.contains("AAAA"), "clamp keeps the head of the hit chunk");
        assertFalse(block.contains("BBBB"), "overflow past the budget is dropped");
    }

    @Test
    void verbatimEvidenceExcerptIsNotRepeated() {
        OriginalTextBlockAssembler.Anchor anchor =
                new OriginalTextBlockAssembler.Anchor(7L, List.of(12L), "第二块正文内容");
        String block = assembler.assemble(anchor, chunks(), 10000);
        assertEquals(1, occurrences(block, "第二块正文内容"), "excerpt == chunk text emits one copy");
    }

    // ---- : chapter-aligned injection window. A section rule often
    // spans several chunks of one chapter; the legacy ±1 window anchored at
    // the tail chunk never reaches the field table at the chapter head
    // (live incident doc 9085 chunk 78 vs seq75-78). Flag on widens the
    // window to the whole sibling set of each hit chunk's chapter while
    // keeping the ±1 rows (a superset, nothing is lost); flag off must stay
    // byte-identical to the legacy window; a blank chapter has no sibling
    // group to widen into, so it stays on the ±1 face. ----

    private static List<Map<String, Object>> chapterChunks() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(GenTestFixtures.chunkRow(71L, 1, "3.2 主营品牌资料新增", "字段表首块：品牌编码规则"));
        rows.add(GenTestFixtures.chunkRow(72L, 2, "3.2 主营品牌资料新增", "字段表续块：品牌名称长度"));
        rows.add(GenTestFixtures.chunkRow(73L, 3, "3.2 主营品牌资料新增", "校验规则：品牌编码唯一"));
        rows.add(GenTestFixtures.chunkRow(74L, 4, "3.2 主营品牌资料新增", "示例：新增主营品牌操作步骤"));
        rows.add(GenTestFixtures.chunkRow(75L, 5, "3.3 门店资料", "下一章节首块正文"));
        return rows;
    }

    @Test
    void chapterAlignedWindowPullsWholeChapterSiblings() {
        OriginalTextBlockAssembler.Anchor anchor =
                new OriginalTextBlockAssembler.Anchor(9085L, List.of(74L), null);
        String block = assembler.assemble(anchor, chapterChunks(), 10000, true);
        assertTrue(block.contains("字段表首块：品牌编码规则"),
                "chapter head block must ride in when the flag is on");
        assertTrue(block.contains("字段表续块：品牌名称长度"), "mid-chapter block must ride in");
        assertTrue(block.contains("示例：新增主营品牌操作步骤"), "hit chunk stays injected");
        assertTrue(block.contains("下一章节首块正文"), "the legacy ±1 neighbour is never dropped");
        int head = block.indexOf("字段表首块：品牌编码规则");
        int hit = block.indexOf("示例：新增主营品牌操作步骤");
        int next = block.indexOf("下一章节首块正文");
        assertTrue(head < hit && hit < next, "emission keeps ascending document order");
    }

    @Test
    void flagOffKeepsLegacyWindowByteIdentical() {
        OriginalTextBlockAssembler.Anchor anchor =
                new OriginalTextBlockAssembler.Anchor(9085L, List.of(74L), null);
        String off = assembler.assemble(anchor, chapterChunks(), 10000, false);
        assertEquals(assembler.assemble(anchor, chapterChunks(), 10000), off,
                "the three-arg legacy face and flag-off must render the same bytes");
        assertFalse(off.contains("字段表首块：品牌编码规则"), "legacy window stops at ±1");
        assertTrue(off.contains("校验规则：品牌编码唯一"), "legacy previous neighbour untouched");
    }

    @Test
    void blankChapterStaysOnLegacyWindowWhenAlignedModeOn() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(GenTestFixtures.chunkRow(81L, 1, "", "无章节首块正文"));
        rows.add(GenTestFixtures.chunkRow(82L, 2, "", "无章节中块正文"));
        rows.add(GenTestFixtures.chunkRow(83L, 3, "", "无章节尾块正文"));
        rows.add(GenTestFixtures.chunkRow(84L, 4, "", "无章节末块正文"));
        OriginalTextBlockAssembler.Anchor anchor =
                new OriginalTextBlockAssembler.Anchor(7L, List.of(82L), null);
        String aligned = assembler.assemble(anchor, rows, 10000, true);
        String legacy = assembler.assemble(anchor, rows, 10000, false);
        assertEquals(legacy, aligned,
                "blank chapter labels group nothing - the widened face falls back to ±1");
    }

    @Test
    void evidenceLocatedHitsAlsoWidenWithTheirChapter() {
        OriginalTextBlockAssembler.Anchor anchor = new OriginalTextBlockAssembler.Anchor(
                9085L, List.of(), "字段表续块：品牌名称长度");
        String block = assembler.assemble(anchor, chapterChunks(), 10000, true);
        assertTrue(block.contains("示例：新增主营品牌操作步骤"),
                "excerpt-located hits widen through the same chapter face");
        assertTrue(block.contains("字段表首块：品牌编码规则"), "chapter head rides in via fallback too");
    }

    @Test
    void alignedWindowStillRespectsBudgetClamp() {
        OriginalTextBlockAssembler.Anchor anchor =
                new OriginalTextBlockAssembler.Anchor(9085L, List.of(74L), null);
        String block = assembler.assemble(anchor, chapterChunks(), 80, true);
        assertTrue(block.contains("（内容已截断）"),
                "a widened window is still clamped honestly by the shared budget");
    }

    private static int occurrences(String haystack, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int at = haystack.indexOf(needle, from);
            if (at < 0) {
                return count;
            }
            count++;
            from = at + needle.length();
        }
    }
}
