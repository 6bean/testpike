package com.sixbean.testpike.aiservice.aichat.domain.component;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration-file fallback for the ai.ctx.* long-context key group
 * (AP batch six-layer chain: seed / K8 hot value wins, this file is the
 * last tier — same discipline as KnowledgeSearchProperties). Values mirror the
 * adjudication sheet defaults (2026-10-26: tool-block 2000 / keep-recent 6 /
 * memory 30).
 */
@ConfigurationProperties(prefix = "ai.ctx")
public class AiCtxProperties {

    /** Per-tool-result token budget before the block is downgraded to head/tail + pointer. */
    private int toolBlockMaxTokens = 2000;

    /** Newest dialog turns kept as raw text; anything older folds into the rolling summary. */
    private int sessionKeepRecentTurns = 6;

    /** Cap of injected project-memory items (never a full system-prompt dump). */
    private int memoryMaxItems = 30;

    /** Over-budget self-heal (pre-flight fold + provider-400 summary retry) ships ON. */
    private boolean healOverBudget = true;

    /** Pre-flight fold line as percent of the routed endpoint prompt limit. */
    private int healPreflightRatio = 95;

    public int getToolBlockMaxTokens() {
        return toolBlockMaxTokens;
    }

    public void setToolBlockMaxTokens(int toolBlockMaxTokens) {
        this.toolBlockMaxTokens = toolBlockMaxTokens;
    }

    public int getSessionKeepRecentTurns() {
        return sessionKeepRecentTurns;
    }

    public void setSessionKeepRecentTurns(int sessionKeepRecentTurns) {
        this.sessionKeepRecentTurns = sessionKeepRecentTurns;
    }

    public int getMemoryMaxItems() {
        return memoryMaxItems;
    }

    public void setMemoryMaxItems(int memoryMaxItems) {
        this.memoryMaxItems = memoryMaxItems;
    }

    public boolean isHealOverBudget() {
        return healOverBudget;
    }

    public void setHealOverBudget(boolean healOverBudget) {
        this.healOverBudget = healOverBudget;
    }

    public int getHealPreflightRatio() {
        return healPreflightRatio;
    }

    public void setHealPreflightRatio(int healPreflightRatio) {
        this.healPreflightRatio = healPreflightRatio;
    }
}
