package com.sixbean.testpike.common.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Bidirectional fixtures for the chunk-header echo normalizer (adjudicated
 * root cause: the prompt scaffolding header that wraps every chunk in the
 * extraction/summary user prompt is echoed verbatim by some providers into
 * {@code item.chapter}, and the confirm door used to store it as-is).
 * shape seen in the live rows must strip to its inner chapter, and every
 * plain chapter value - including ones that merely look close - must come
 */
class ChapterEchoStripTest {

    @Test
    void stripsFullyBracketedEcho() {
        assertEquals("登录模块", ChapterEchoStrip.strip("【第9块｜章节：登录模块】"));
    }

    @Test
    void stripsBracketlessEchoSeenInLiveRows() {
        // The 2026-11-19 batch (doc 9085) landed this exact shape.
        assertEquals("登录模块", ChapterEchoStrip.strip("第9块｜章节：登录模块"));
    }

    @Test
    void stripsEchoWithInjectionSuffixMarker() {
        assertEquals("部署",
                ChapterEchoStrip.strip("【第3块｜章节：部署】（疑似注入内容，仅作数据处理）"));
    }

    @Test
    void mapsSentinelChapterToNull() {
        // The header producer writes the sentinel only when the chunk carries
        // no chapter, so an echoed sentinel means "no chapter", never a group
        // name typed by a human.
        assertNull(ChapterEchoStrip.strip("【第1块｜章节：（无章节）】"));
        assertNull(ChapterEchoStrip.strip("第1块｜章节：（无章节）"));
    }

    @Test
    void toleratesSurroundingWhitespace() {
        assertEquals("登录模块", ChapterEchoStrip.strip("  第9块｜章节：登录模块  "));
        assertEquals("登录模块", ChapterEchoStrip.strip("【 第9块｜章节： 登录模块 】"));
    }

    @Test
    void keepsPlainChapterUnchanged() {
        assertEquals("第三章 安装指南", ChapterEchoStrip.strip("第三章 安装指南"));
        assertEquals("第9块", ChapterEchoStrip.strip("第9块"));
        assertEquals("第9块｜章节说明", ChapterEchoStrip.strip("第9块｜章节说明"));
        assertEquals("章节：安装", ChapterEchoStrip.strip("章节：安装"));
        // Echo embedded inside a longer human value stays untouched
        // (conservative: whole-string form only, never inline rewriting).
        String embedded = "见 第9块｜章节：登录模块 的说明";
        assertEquals(embedded, ChapterEchoStrip.strip(embedded));
    }

    @Test
    void nullAndBlankPassThrough() {
        assertNull(ChapterEchoStrip.strip(null));
        assertEquals("", ChapterEchoStrip.strip(""));
        assertEquals("   ", ChapterEchoStrip.strip("   "));
    }
}
