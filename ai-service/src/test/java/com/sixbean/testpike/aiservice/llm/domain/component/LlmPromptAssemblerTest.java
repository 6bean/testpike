package com.sixbean.testpike.aiservice.llm.domain.component;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


class LlmPromptAssemblerTest {

    private final TokenCounter counter = new TokenCounter();
    private final LlmPromptAssembler assembler = new LlmPromptAssembler(counter);

    
    private static final String BODY_A = "接口超时阈值统一使用 30 秒。".repeat(20);
    private static final String BODY_B = "登录用例必须包含验证码步骤。".repeat(20);

    private static SkillInjectionItem item(long id, int role, int priority, int enabled,
                                           String name, String body) {
        return new SkillInjectionItem(id, 2, role, priority, enabled, name, body);
    }

    
    @Test
    void role1脚本开发仅注入stage2() {
        List<SkillInjectionItem> items = List.of(item(11, 1, 0, 1, "脚本规范", BODY_A));
        assertTrue(assembler.assemble(SkillStage.SCRIPT_GEN, 100_000, items).segment().contains("脚本规范"),
                "stage2 应含 role=1 SKILL");
        assertFalse(assembler.assemble(SkillStage.CASE_GEN, 100_000, items).segment().contains("脚本规范"),
                "stage1 不应含 role=1 SKILL");
        assertFalse(assembler.assemble(SkillStage.F2_EXTRACT, 100_000, items).usageTraces().size() == 1,
                "F2 不产 role=1 留痕");
        assertFalse(assembler.assemble(SkillStage.REPAIR_LOOP, 100_000, items).segment().contains("脚本规范"),
                "修复循环不应含 role=1 SKILL");
    }

    
    @Test
    void role2测试SKILL仅注入修复循环() {
        List<SkillInjectionItem> items = List.of(item(22, 2, 0, 1, "执行判定", BODY_A));
        assertTrue(assembler.assemble(SkillStage.REPAIR_LOOP, 100_000, items).segment().contains("执行判定"));
        assertFalse(assembler.assemble(SkillStage.CASE_GEN, 100_000, items).segment().contains("执行判定"));
        assertFalse(assembler.assemble(SkillStage.SCRIPT_GEN, 100_000, items).segment().contains("执行判定"));
    }

    
    @Test
    void role3通用全链路注入() {
        List<SkillInjectionItem> items = List.of(item(33, 3, 0, 1, "通用规范", BODY_A));
        for (SkillStage stage : List.of(SkillStage.F2_EXTRACT, SkillStage.CASE_GEN,
                SkillStage.SCRIPT_GEN, SkillStage.REPAIR_LOOP)) {
            assertTrue(assembler.assemble(stage, 100_000, items).segment().contains("通用规范"),
                    "role=3 应进全阶段 stage=" + stage);
        }
    }

 
    @Test
    void 停用SKILL排除零留痕() {
        List<SkillInjectionItem> items = List.of(item(44, 3, 0, 0, "停用项", BODY_A));
        SkillAssembly r = assembler.assemble(SkillStage.SCRIPT_GEN, 100_000, items);
        assertEquals("", r.segment());
        assertTrue(r.usageTraces().isEmpty(), "停用不产留痕（未消费）");
    }

    
    @Test
    void 优先级降序装配() {
        List<SkillInjectionItem> items = List.of(
                item(1, 3, 10, 1, "低优先", BODY_B),
                item(2, 3, 90, 1, "高优先", BODY_A),
                item(3, 3, 50, 1, "中优先", BODY_B));
        SkillAssembly r = assembler.assemble(SkillStage.CASE_GEN, 1_000_000, items);
        int hi = r.segment().indexOf("高优先");
        int mid = r.segment().indexOf("中优先");
        int lo = r.segment().indexOf("低优先");
        assertTrue(hi >= 0 && hi < mid && mid < lo, "段内顺序应为 高→中→低: " + r.segment());
    }

    
    @Test
    void 超预算按优先级丢弃低者warn人话不抛() {
        SkillInjectionItem hi = item(1, 3, 90, 1, "甲", BODY_A);
        SkillInjectionItem lo = item(2, 3, 10, 1, "乙", BODY_B);
        int estA = assembler.assemble(SkillStage.CASE_GEN, 1_000_000, List.of(hi)).estTokens();
        int estAB = assembler.assemble(SkillStage.CASE_GEN, 1_000_000, List.of(hi, lo)).estTokens();
        int budget = estA + (estAB - estA) / 2;
        SkillAssembly r = assembler.assemble(SkillStage.CASE_GEN, budget, List.of(lo, hi));
        assertTrue(r.segment().contains("甲"), "高优先级必须保留");
        assertFalse(r.segment().contains("乙"), "低优先级应被丢弃");
        assertEquals(1, r.injectedCount());
        assertEquals(1, r.droppedCount());
        assertNotNullWarn(r.warnMessage());
        assertTrue(r.warnMessage().contains("已省略 1 项"), "warn 人话含省略数: " + r.warnMessage());
        assertEquals(0, trace(r, 1).dropped());
        assertEquals(1, trace(r, 2).dropped());
    }

    
    @Test
    void 预算极小全丢弃段空warn全量() {
        List<SkillInjectionItem> items = List.of(
                item(7, 3, 0, 1, "七", BODY_A), item(8, 3, 0, 1, "八", BODY_B));
        SkillAssembly r = assembler.assemble(SkillStage.SCRIPT_GEN, 5, items);
        assertEquals("", r.segment());
        assertEquals(0, r.injectedCount());
        assertEquals(2, r.droppedCount());
        assertTrue(r.warnMessage().contains("已省略 2 项"), r.warnMessage());
        assertEquals(0, r.estTokens());
    }

    
    @Test
    void 空集合零破坏() {
        SkillAssembly r = assembler.assemble(SkillStage.CASE_GEN, 100_000, List.of());
        assertEquals("", r.segment());
        assertTrue(r.usageTraces().isEmpty());
        assertNull(r.warnMessage());
        assertEquals(0, r.injectedCount());
        assertEquals("", assembler.assemble(SkillStage.CASE_GEN, 100_000, null).segment());
    }

    
    @Test
    void 留痕含版本快照与阶段码() {
        List<SkillInjectionItem> items = List.of(item(99, 1, 0, 1, "规范", BODY_A));
        SkillAssembly r = assembler.assemble(SkillStage.SCRIPT_GEN, 1_000_000, items);
        SkillUsageTrace t = trace(r, 99);
        assertEquals(2, t.version(), "消费时版本快照=item.version");
        assertEquals(3, t.stage(), "SCRIPT_GEN→stage 列值 3（V38 枚举）");
        assertEquals(SkillStage.SCRIPT_GEN.code(), t.stage());
    }

    
    @Test
    void 空白正文排除() {
        List<SkillInjectionItem> items = List.of(item(5, 3, 0, 1, "空", "   "));
        assertEquals("", assembler.assemble(SkillStage.CASE_GEN, 100_000, items).segment());
    }

    private static void assertNotNullWarn(String w) {
        assertTrue(w != null && !w.isBlank(), "warn 不应为空");
    }

    private static SkillUsageTrace trace(SkillAssembly r, long skillId) {
        return r.usageTraces().stream()
                .filter(t -> t.skillId() == skillId)
                .findFirst()
                .orElseThrow(() -> new AssertionError("留痕缺失 skillId=" + skillId));
    }
}
