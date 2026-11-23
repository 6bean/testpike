package com.sixbean.testpike.aiservice.gen.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;


class PromptWordingAdoptionTest {

    private static final long PROJECT_ID = 3L;

    private final CoreGateway coreGateway = mock(CoreGateway.class);
    private final PromptWordingAdoption adoption =
            new PromptWordingAdoption(coreGateway, new ObjectMapper());

    private void stubPairs(List<CoreGateway.WordingPair> pairs) {
        when(coreGateway.wordingPairs(eq(PROJECT_ID), anyInt())).thenReturn(pairs);
    }

    @Test
    void 采纳对拼装含标题锚反馈半句与字段修正行() {
        stubPairs(List.of(new CoreGateway.WordingPair(81L, 82L,
                "标题表述不准确，应体现登录场景",
                "[{\"field\":\"title\",\"label\":\"用例名称\",\"before\":\"登录\","
                        + "\"after\":\"账号密码登录成功\"}]",
                "2026-09-25 10:00:00")));

        String segment = adoption.wordingSegment(PROJECT_ID);

        assertTrue(segment.startsWith("期望表述约束（评审采纳，必须遵循）："),
                "标题锚缺失：\n" + segment);
        assertTrue(segment.contains("标题表述不准确，应体现登录场景"), "反馈表述半句未注入");
        assertTrue(segment.contains("用例名称"), "changes[].label 未注入");
        assertTrue(segment.contains("账号密码登录成功"), "changes[].after 未注入");
    }

    @Test
    void 零采纳对返回空段不出现标题锚() {
        stubPairs(List.of());
        assertEquals("", adoption.wordingSegment(PROJECT_ID));
    }

    @Test
    void 读路径异常空段failsoft禁炸生成() {
        when(coreGateway.wordingPairs(eq(PROJECT_ID), anyInt()))
                .thenThrow(new RuntimeException("core 不可达"));
        assertEquals("", adoption.wordingSegment(PROJECT_ID),
                "读路径异常必须降级空段（IT-D4-01 禁炸生成主链）");
    }

    @Test
    void 差异JSON解析失败保留反馈半句跳过修正行() {
        stubPairs(List.of(new CoreGateway.WordingPair(83L, 84L,
                "预期结果表述口语化", "{not valid json", "2026-09-25 10:00:00")));

        String segment = adoption.wordingSegment(PROJECT_ID);

        assertTrue(segment.contains("预期结果表述口语化"), "解析失败不得吞掉反馈半句");
        assertFalse(segment.contains("not valid"), "坏 JSON 原文不得泄漏进 prompt");
    }

    @Test
    void 无配对差异仅反馈表述半句() {
        stubPairs(List.of(new CoreGateway.WordingPair(85L, null,
                "步骤描述缺主体", null, "2026-09-25 10:00:00")));

        String segment = adoption.wordingSegment(PROJECT_ID);

        assertTrue(segment.contains("步骤描述缺主体"));
        assertEquals(-1, segment.indexOf("字段修正"), "无差异行不得出现修正行");
    }

    @Test
    void 全空内容对不产标题锚() {
        stubPairs(List.of(new CoreGateway.WordingPair(86L, null, " ", null, null)));
        assertEquals("", adoption.wordingSegment(PROJECT_ID),
                "remark 空白+无差异=该对无有效内容，禁悬空标题锚");
    }

    @Test
    void 多对多条修正行逐项展开() {
        stubPairs(List.of(
                new CoreGateway.WordingPair(87L, 88L, "标题太泛",
                        "[{\"field\":\"title\",\"label\":\"用例名称\",\"before\":\"a\",\"after\":\"b\"},"
                                + "{\"field\":\"expectedResult\",\"label\":\"预期结果\","
                                + "\"before\":\"c\",\"after\":\"d\"}]", null),
                new CoreGateway.WordingPair(89L, null, "前置缺环境", null, null)));

        String segment = adoption.wordingSegment(PROJECT_ID);

        assertTrue(segment.contains("用例名称") && segment.contains("预期结果"),
                "changes 数组多项须逐项展开：\n" + segment);
        assertTrue(segment.contains("前置缺环境"), "第二对的反馈半句须注入");
    }
}
