package com.sixbean.testpike.aiservice.llm.domain.component;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Project-memory assembly contract : distilled memory
 * entries ride the SINGLE SKILL assembly channel (kind=1 items) — they share
 * the budget/drop machinery but NEVER produce tp_skill_usage traces (memory
 * rows are not SKILLs; their accounting face is tp_ai_ctx_memory.hit_count).
 * <p>Assertions: ① memory bodies visible in the segment after all SKILLs;
 * ② newest-first order inside memory; ③ traces contain skill ids only;
 * ④ budget-dropped memory is NOT counted as a hit.
 */
class LlmPromptAssemblerMemoryTest {

    private final LlmPromptAssembler assembler = new LlmPromptAssembler(new TokenCounter());

    private static SkillInjectionItem skill(long id, int priority, String body) {
        return new SkillInjectionItem(id, 1, 3, priority, 1, "SKILL-" + id, body,
                SkillInjectionItem.KIND_SKILL);
    }

    private static SkillInjectionItem memory(long id, String body) {
        return new SkillInjectionItem(id, 0, 3, 0, 1, "项目记忆", body,
                SkillInjectionItem.KIND_MEMORY);
    }

    @Test
    void 记忆条目进注入段但不产SKILL留痕_最新在前() {
        SkillAssembly result = assembler.assemble(SkillStage.CASE_GEN, 4000, List.of(
                skill(11, 5, "接口断言必须显式"),
                memory(21, "接口命名用驼峰"),
                memory(22, "禁用 sleep 硬等待")));

        assertTrue(result.segment().contains("接口命名用驼峰"), "记忆一缺失: " + result.segment());
        assertTrue(result.segment().contains("禁用 sleep 硬等待"), "记忆二缺失: " + result.segment());
        assertEquals(List.of(11L),
                result.usageTraces().stream().map(SkillUsageTrace::skillId).toList(),
                "记忆条目绝不落 tp_skill_usage 留痕");
        assertEquals(List.of(22L, 21L), result.memoryHitIds(), "注入命中=最新在前");
    }

    @Test
    void 记忆超预算丢弃不计命中() {
        SkillAssembly result = assembler.assemble(SkillStage.CASE_GEN, 8, List.of(
                skill(11, 5, "长正文长正文长正文长正文长正文长正文"),
                memory(21, "同样很长的项目记忆内容同样很长")));

        assertEquals(List.of(), result.memoryHitIds(), "丢弃的记忆不计命中");
        assertEquals(2, result.droppedCount());
        assertEquals(1, result.usageTraces().size(), "留痕只含 SKILL 一项");
    }
}
