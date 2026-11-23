package com.sixbean.testpike.core.webelement.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.appauto.api.dto.AppautoStepSuggestionInput;
import com.sixbean.testpike.core.appauto.domain.service.AppautoStepHealingService;
import com.sixbean.testpike.core.webelement.api.dto.WebElementAnchorInput;
import com.sixbean.testpike.core.webelement.api.dto.WebElementView;
import com.sixbean.testpike.core.webelement.domain.service.WebElementService;
import com.sixbean.testpike.common.exception.ServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Re-anchor tickets for the exploration mismatch set (WE batch S3, :
 * {@link ExploreAnchorReconcile} (its detector half) hands over the entry ids
 * whose judgable anchors all missed the fresh candidate pool; this component
 * files one element repair suggestion per id through the SINGLE A9 door —
 * {@link AppautoStepHealingService#record} with the WEB shape designed in S2
 * (source "dom" = anchor rebuilt from a fresh DOM snapshot, failure class 2,
 * no carried action — an element repair may only re-point the locator). No
 * second suggestion surface exists: tickets land on the same board whose
 * adoptWeb chain merges the approved anchor and revives the entry.
 *
 * <p>Honesty pins: a ticket needs evidence. The proposal anchor comes from a
 * pool candidate whose visible text equals the entry name (the same-text
 * strong-evidence tier of design A3); stable id/testid css spellings survive
 * as the stronger css proposal, positional chains fall back to the visible
 * text. Rows with no same-named candidate are counted as skipped, never
 * fabricated into a ticket. One row's business refusal (e.g. the door's
 * cross-project guard) is logged and skipped so its siblings still land —
 * the step journal above already succeeded and the AI loop must never be
 * bounced into the duplicate-seq replay hole (same posture as slices 2 and 3).
 */
@Component
public class ExploreReanchorProposal {

    private static final Logger log = LoggerFactory.getLogger(ExploreReanchorProposal.class);

    /** Only UI-change-suspect failures may hold a suggestion (A9 value domain). */
    private static final int FAILURE_CLASS_UI_CHANGE = 2;

    /** Provenance of a pool-rebuilt anchor: the dom value of the shared source domain. */
    private static final String SOURCE_DOM = "dom";

    /** Board text: what happened, what the ticket proposes, what approval does. */
    private static final String REASON =
            "探索比对发现该条目全部锚与当前页面失配，新页面快照中存在同名可交互元素，"
                    + "建议按候选定位重锚；批准后条目回到已确认，引用它的用例统一受益。";

    /** Per-row result tally for the journal line and the tests. */
    public record Outcome(int filed, int skipped) {
    }

    private final WebElementService webElementService;
    private final AppautoStepHealingService healingService;
    private final ObjectMapper mapper;

    public ExploreReanchorProposal(WebElementService webElementService,
                                   AppautoStepHealingService healingService,
                                   ObjectMapper mapper) {
        this.webElementService = webElementService;
        this.healingService = healingService;
        this.mapper = mapper;
    }

    /**
     * File one WEB repair suggestion per evidenced mismatch row. The
     * exploration step's own act task is the ownership anchor (its project
     * must carry the ledger entry — enforced by the record door, never
     * trusted from here). Duplicate tickets are structurally suppressed
     * upstream: reconcile only reports CONFIRMED rows and the mismatch side
     * has already flipped the entry to stale.
     */
    public Outcome file(long projectId, int seq, long execTaskId,
                        List<Long> mismatched, String poolJson) {
        if (mismatched == null || mismatched.isEmpty()) {
            return new Outcome(0, 0);
        }
        ExploreAnchorReconcile.Pool pool = ExploreAnchorReconcile.parsePool(poolJson);
        int filed = 0;
        int skipped = 0;
        for (Long elementId : mismatched) {
            try {
                WebElementView entry = webElementService.findById(elementId);
                String targetJson = proposedAnchorJson(entry, pool);
                if (targetJson == null) {
                    skipped++;
                    log.info("失配重锚无同名候选，不出单（禁造假锚） projectId={} elementId={}",
                            projectId, elementId);
                    continue;
                }
                healingService.record(buildInput(execTaskId, seq, entry, targetJson));
                filed++;
            } catch (ServiceException business) {
                skipped++;
                log.warn("失配重锚建议单被业务闸拒绝（跨项目/并发等），不影响同行其他条目 "
                        + "projectId={} elementId={} reason={}",
                        projectId, elementId, business.getMessage());
            }
        }
        return new Outcome(filed, skipped);
    }

    /**
     * Evidence check + proposal anchor for one mismatched row, or null when
     * no honest anchor can be derived (blank name, missing anchors, no
     * same-named candidate in the pool).
     */
    private String proposedAnchorJson(WebElementView entry, ExploreAnchorReconcile.Pool pool) {
        String name = entry.name() == null ? "" : entry.name().trim();
        if (name.isEmpty() || entry.anchors() == null || entry.anchors().isEmpty()) {
            return null;
        }
        ExploreAnchorReconcile.Candidate matched = null;
        for (ExploreAnchorReconcile.Candidate candidate : pool.candidates()) {
            if (candidate.text() != null && !candidate.text().isBlank()
                    && candidate.text().trim().equals(name)) {
                matched = candidate;
                break;
            }
        }
        if (matched == null) {
            return null;
        }
        Map<String, Object> proposed = new LinkedHashMap<>();
        if (isStableCss(matched.cssTarget())) {
            proposed.put("type", "css");
            proposed.put("value", matched.cssTarget());
        } else {
            proposed.put("type", "text");
            proposed.put("value", name);
        }
        // The adopt door writes this anchor into the ledger, whose validation
        // demands an explicit strength (0..1). Ride the shared structural prior
        // of the proposed type so a ticket carries exactly the weight the
        // dispatcher would give it, instead of dying at human approval.
        proposed.put("strength", WebElementDispatchSnapshot.structuralPrior(
                (String) proposed.get("type")));
        try {
            return mapper.writeValueAsString(proposed);
        } catch (Exception unpersistable) {
            return null;
        }
    }

    /** Stable-locator spellings only: #id and [data-test...]; chains never are. */
    private static boolean isStableCss(String cssTarget) {
        if (cssTarget == null || cssTarget.isBlank()) {
            return false;
        }
        return cssTarget.startsWith("#") || cssTarget.startsWith("[data-test");
    }

    /** Ticket payload on the S2 WEB shape: re-point only, no action, no coords. */
    private AppautoStepSuggestionInput buildInput(long execTaskId, int seq, WebElementView entry,
                                                  String targetJson) {
        WebElementAnchorInput failed = entry.anchors().get(0);
        return new AppautoStepSuggestionInput(execTaskId, Math.max(seq, 1), FAILURE_CLASS_UI_CHANGE,
                failed.type() + "=" + failed.value(), SOURCE_DOM, null, targetJson, REASON,
                null, null, "WEB", entry.id());
    }

    /**
     * The explore-act task id journaled inside the step result
     * (ExploreLoopKernel writes {@code {"execTaskId":...}} for every built-in
     * act, and a candidate pool can only come from such a step). Missing or
     * malformed = 0: without the ownership anchor no ticket is filed.
     */
    public static long readExecTaskId(String stepResultJson) {
        if (stepResultJson == null || stepResultJson.isBlank()) {
            return 0L;
        }
        try {
            return new ObjectMapper().readTree(stepResultJson)
                    .path("execTaskId").asLong(0L);
        } catch (Exception malformed) {
            return 0L;
        }
    }
}
