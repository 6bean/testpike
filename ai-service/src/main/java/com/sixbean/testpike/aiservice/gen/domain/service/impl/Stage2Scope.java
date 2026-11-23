package com.sixbean.testpike.aiservice.gen.domain.service.impl;

import com.sixbean.testpike.aiservice.gen.api.dto.GeneratedCase;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmEndpointConfig;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackNotice;
import com.sixbean.testpike.common.mq.AiGenMessage;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Frozen stage-2 inputs of one generation task .
 *
 * <p>Everything here is assembled ONCE per task and then reused by every
 * step-group pass: the SKILL system prefixes (AP-6 prefix stability, so the
 * provider prompt cache keeps hitting across groups), the probed page-inspect
 * and APP segments, the failed-repair fold-back, the case-level few-shot and
 * the project resource pool. Only the case view changes per group, which is
 * passed separately — a group must never re-probe a bridge (that would burn
 * the collection budget once per group).
 *
 * <p>{@code lastDdl} / {@code sqlGenOn} are the existing per-call mutable
 * slots (K8 hot read inside the proposer), carried here so the proposer
 * signature stays short; {@code markedModel} is the fallback-model backfill
 * guard shared by stage 1 and every stage-2 pass.
 */
record Stage2Scope(AiGenMessage message,
                   GeneratedCase fullCase,
                   LlmEndpointConfig primary,
                   LlmFallbackNotice notice,
                   AtomicReference<String> markedModel,
                   String inspectSegment,
                   String appSegment,
                   String scriptGenSystem,
                   String[] repairLoopSystem,
                   String priorRepairSegment,
                   String stage2Fewshot,
                   String resourceSegment,
                   String ledgerSummarySegment,
                   List<String> lastDdl,
                   boolean[] sqlGenOn,
                   String originalSegment,
                   String sliceNote) {

    /** Same frozen inputs, one step group addressed (empty note = whole case). */
    Stage2Scope forGroup(String groupNote) {
        return new Stage2Scope(message, fullCase, primary, notice, markedModel, inspectSegment,
                appSegment, scriptGenSystem, repairLoopSystem, priorRepairSegment, stage2Fewshot,
                resourceSegment, ledgerSummarySegment, lastDdl, sqlGenOn, originalSegment,
                groupNote == null ? "" : groupNote);
    }
}
