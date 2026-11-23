package com.sixbean.testpike.aiservice.llm.domain.component;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * <ol>
 * </ol>
 *
 */
public class LlmPromptAssembler {

    private static final Logger log = LoggerFactory.getLogger(LlmPromptAssembler.class);

    
    static final String SECTION_HEADER = "项目 SKILL 规范（生成时必须遵循）：\n";

    private final TokenEstimator estimator;

    public LlmPromptAssembler(TokenEstimator estimator) {
        this.estimator = estimator;
    }

    
    public SkillAssembly assemble(SkillStage stage, int budgetTokens, List<SkillInjectionItem> items) {
        List<SkillInjectionItem> candidates = eligible(stage, items);
        // Assembly order: SKILL rows first, then project memory (kind ASC); inside
        // each kind priority DESC; tie-break = skillId ASC for SKILLs (S5 contract)
        // and NEWEST-first for memory rows .
        candidates.sort(Comparator.comparingInt(SkillInjectionItem::kind)
                .thenComparing(Comparator.comparingInt(SkillInjectionItem::priority).reversed())
                .thenComparingLong(i -> i.kind() == SkillInjectionItem.KIND_MEMORY
                        ? -i.skillId() : i.skillId()));

        StringBuilder segment = new StringBuilder();
        List<SkillUsageTrace> traces = new ArrayList<>();
        List<Long> memoryHits = new ArrayList<>();
        int injected = 0;
        int dropped = 0;
        for (SkillInjectionItem item : candidates) {
            String block = "【" + item.name() + "】\n" + item.body().strip() + "\n";
            int estIfAdded = estimator.countTokens(segment + SECTION_HEADER + block);
            boolean memory = item.kind() == SkillInjectionItem.KIND_MEMORY;
            if (estIfAdded <= budgetTokens) {
                segment.append(block);
                injected++;
                // Memory rows are not SKILLs: never traced into tp_skill_usage
                // (their ids share no numbering with tp_skill — mixed writes would
                // corrupt the ledger). Hits account ONLY entries that made it in.
                if (memory) {
                    memoryHits.add(item.skillId());
                } else {
                    traces.add(new SkillUsageTrace(item.skillId(), item.version(),
                            stage.code(), 0));
                }
            } else {
                dropped++;
                if (!memory) {
                    traces.add(new SkillUsageTrace(item.skillId(), item.version(),
                            stage.code(), 1));
                }
            }
        }

        String result = injected == 0 ? "" : SECTION_HEADER + segment;
        String warn = dropped == 0 ? null
                : "SKILL 超预算已省略 " + dropped + " 项（保留高优先级 " + injected + " 项）";
        if (dropped > 0) {
            log.warn("SKILL 装配超预算丢弃 stage={} budget={} injected={} dropped={}",
                    stage, budgetTokens, injected, dropped);
        }
        return new SkillAssembly(result, estimator.countTokens(result), injected, dropped,
                warn, traces, memoryHits);
    }

    
    private List<SkillInjectionItem> eligible(SkillStage stage, List<SkillInjectionItem> items) {
        List<SkillInjectionItem> out = new ArrayList<>();
        if (items == null) {
            return out;
        }
        for (SkillInjectionItem item : items) {
            if (item.enabled() != 1 || !stage.accepts(item.role())) {
                continue;
            }
            if (item.body() == null || item.body().isBlank()) {
                continue;
            }
            out.add(item);
        }
        return out;
    }
}
