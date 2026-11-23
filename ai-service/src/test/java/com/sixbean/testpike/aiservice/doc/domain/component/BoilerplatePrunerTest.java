package com.sixbean.testpike.aiservice.doc.domain.component;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


class BoilerplatePrunerTest {

    private final BoilerplatePruner pruner = new BoilerplatePruner();

    private static Map<String, Object> chunk(int seq, String chapter, String content) {
        return Map.of("seq", seq, "chapter", chapter, "content", content);
    }

    private static String contentOf(Map<String, Object> chunk) {
        return String.valueOf(chunk.get("content"));
    }

 
    @Test
    void prune_结构样板行剪除正文保留() {
        List<Map<String, Object>> in = List.of(chunk(1, "概述",
                """
                # 第1章 概述
                系统应支持账号密码登录，连续失败五次锁定十五分钟。
                ---
                第 3 页 共 12 页
                1 / 12
                ***
                """));
        String out = contentOf(pruner.prune(in).get(0));
        assertTrue(out.contains("# 第1章 概述"), "Markdown 标题=内容，禁剪");
        assertTrue(out.contains("系统应支持账号密码登录"));
        assertFalse(out.contains("第 3 页"));
        assertFalse(out.contains("1 / 12"));
        assertFalse(out.contains("---"));
        assertFalse(out.contains("***"));
    }

 
    @Test
    void prune_跨块重复需求条款零丢失() {
        String clause = "本系统应满足 GB/T 22239 等保三级要求。";
        List<Map<String, Object>> in = List.of(
                chunk(1, "登录", "登录模块设计说明。\n" + clause + "\n支持双因素认证。"),
                chunk(2, "审计", "审计模块设计说明。\n" + clause + "\n日志保留一百八十天。"),
                chunk(3, "加密", "加密模块设计说明。\n" + clause + "\n密钥轮换周期九十天。"));
        List<Map<String, Object>> out = pruner.prune(in);
        for (Map<String, Object> c : out) {
            assertTrue(contentOf(c).contains(clause), "重复条款被误剪: " + contentOf(c));
        }
    }

    
    @Test
    void prune_跨块页眉剪除正文同句不剪() {
        String header = "××银行需求规格说明书 V2.3";
        List<Map<String, Object>> in = List.of(
                chunk(1, "A", header + "\n甲一。\n" + header + "\n甲二。\n甲三。"),
                chunk(2, "B", header + "\n正文乙。"),
                chunk(3, "C", header + "\n正文丙。"));
        List<Map<String, Object>> out = pruner.prune(in);
        String first = contentOf(out.get(0));
        assertTrue(first.startsWith("甲一"), "首行页眉应剪除: " + first);
        assertTrue(first.contains("甲二") && first.contains("甲三"));
        assertTrue(first.contains(header), "正文位（非首尾行）同句保留——位置约束");
        for (int i = 1; i < 3; i++) {
            assertFalse(contentOf(out.get(i)).contains(header), "块" + i + " 页眉未剪: " + contentOf(out.get(i)));
        }
    }

 
    @Test
    void prune_幂等且无样板零改动() {
        List<Map<String, Object>> clean = List.of(
                chunk(1, "登录", "系统应支持登录。"),
                chunk(2, "登出", "系统应支持登出。"));
        List<Map<String, Object>> once = pruner.prune(clean);
        assertEquals(contentOf(clean.get(0)), contentOf(once.get(0)));
        assertEquals(contentOf(clean.get(1)), contentOf(once.get(1)));
        assertEquals(List.of(1, 2), once.stream()
                .map(c -> ((Number) c.get("seq")).intValue()).toList());
        assertEquals("登录", once.get(0).get("chapter"));
        List<Map<String, Object>> twice = pruner.prune(once);
        assertEquals(contentOf(once.get(0)), contentOf(twice.get(0)), "剪枝不幂等");
    }

    
    @Test
    void prune_单块不触发跨块规则() {
        String line = "重要提示";
        List<Map<String, Object>> in = List.of(chunk(1, "X", line + "\n正文"));
        assertTrue(contentOf(pruner.prune(in).get(0)).contains(line));
    }

    
    @Test
    void prune_空输入原样返回() {
        assertEquals(List.of(), pruner.prune(List.of()));
        assertTrue(pruner.prune(null).isEmpty());
    }
}
