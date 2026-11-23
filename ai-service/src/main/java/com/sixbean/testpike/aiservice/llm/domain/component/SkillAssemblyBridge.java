package com.sixbean.testpike.aiservice.llm.domain.component;

import com.sixbean.testpike.aiservice.doc.domain.component.ContextBudgetPlanner;
import com.sixbean.testpike.aiservice.aichat.domain.component.AiCtxConfigResolver;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.SkillAssemblyRemoteItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;


@Component
public class SkillAssemblyBridge {

    private static final Logger log = LoggerFactory.getLogger(SkillAssemblyBridge.class);

    private final CoreGateway coreGateway;
    private final LlmPromptAssembler assembler;
    private final SkillUsageRecorder recorder;
    private final AiCtxConfigResolver memoryCap;

    public SkillAssemblyBridge(CoreGateway coreGateway, LlmPromptAssembler assembler,
                               SkillUsageRecorder recorder, AiCtxConfigResolver memoryCap) {
        this.coreGateway = coreGateway;
        this.assembler = assembler;
        this.recorder = recorder;
        this.memoryCap = memoryCap;
    }

    
    public String assemblySegment(SkillStage stage, long projectId, Long taskId,
                                  LlmEndpointConfig endpoint) {
        List<SkillInjectionItem> items;
        try {
            List<SkillAssemblyRemoteItem> remote = coreGateway.getProjectAssembly(projectId);
            items = new ArrayList<>(remote.size());
            for (SkillAssemblyRemoteItem r : remote) {
                items.add(new SkillInjectionItem(r.skillId(), r.version(), r.role(),
                        r.priority(), r.enabled(), r.name(), r.body(), r.kind()));
            }
        } catch (Exception e) {
            log.warn("SKILL 装配取数失败（本阶段零注入，不阻断生成）projectId={} stage={}: {}",
                    projectId, stage, e.getMessage());
            return "";
        }
        if (items.isEmpty()) {
            return "";
        }
        items = trimMemoryToCap(items);
        int docBudget = ContextBudgetPlanner.tokenBudgetOf(
                coreGateway.getSysConfig(ContextBudgetPlanner.BUDGET_CONFIG_KEY));
        int budget = PromptSizeGuard.effectiveLimit(docBudget,
                endpoint == null ? null : endpoint.promptLimitTokens());
        SkillAssembly assembly = assembler.assemble(stage, budget, items);
        if (assembly.warnMessage() != null) {
            log.warn("SKILL 装配 projectId={} stage={}: {}", projectId, stage,
                    assembly.warnMessage());
        }
        recorder.record(taskId, assembly.usageTraces());
        recorder.recordMemoryHits(assembly.memoryHitIds());
        return assembly.segment();
    }

    /**
 * Quantity trim for project-memory rows (AP batch never the
 * full table into the system prompt): K8 {@code ai.ctx.memory-max-items}
 * hot read, newest-first kept / stale tail cut. SKILL rows pass through in
 * their original order (this gate trims memory only; the token budget gate
 * stays single-sourced in {@link LlmPromptAssembler}).
 */
    private List<SkillInjectionItem> trimMemoryToCap(List<SkillInjectionItem> items) {
        List<SkillInjectionItem> memory = new ArrayList<>();
        List<SkillInjectionItem> rest = new ArrayList<>();
        for (SkillInjectionItem item : items) {
            if (item.kind() == SkillInjectionItem.KIND_MEMORY) {
                memory.add(item);
            } else {
                rest.add(item);
            }
        }
        if (memory.isEmpty()) {
            return items;
        }
        memory.sort((a, b) -> Long.compare(b.skillId(), a.skillId()));
        int cap = memoryCap.memoryMaxItems();
        if (memory.size() > cap) {
            log.warn("项目记忆超出上限 {} 条，按最新在前省略 {} 条 projectId={}",
                    cap, memory.size() - cap, rest.isEmpty() ? "?" : rest.get(0).skillId());
            memory = new ArrayList<>(memory.subList(0, cap));
        }
        List<SkillInjectionItem> merged = new ArrayList<>(rest.size() + memory.size());
        merged.addAll(rest);
        merged.addAll(memory);
        return merged;
    }

    
    public String composeSystem(String baseSystemPrompt, SkillStage stage, long projectId,
                                Long taskId, LlmEndpointConfig endpoint) {
        String segment = assemblySegment(stage, projectId, taskId, endpoint);
        return segment.isEmpty() ? baseSystemPrompt : baseSystemPrompt + "\n\n" + segment;
    }
}
