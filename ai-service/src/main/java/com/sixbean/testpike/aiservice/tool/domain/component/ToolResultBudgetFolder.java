package com.sixbean.testpike.aiservice.tool.domain.component;

import com.sixbean.testpike.aiservice.aichat.domain.component.AiCtxConfigResolver;
import com.sixbean.testpike.aiservice.llm.domain.component.TokenEstimator;
import com.sixbean.testpike.aiservice.llm.domain.component.TokenEstimatorRegistry;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * AP-1(1) layered tool-result intake .
 * <p>Every dispatcher backfill passes here once: blocks under the hot key
 * {@code ai.ctx.tool-block-max-tokens} ride through untouched (identity
 * return, zero drift for existing fixtures), oversized blocks are downgraded
 * to a head/tail window whose full text goes to {@link ToolResultExternalizer}
 * and whose pointer names the retrieval anchor, so the ReAct dialog packet
 * stops re-billing a 25k-token tool dump every round.
 * <p>Degrade rules (fail-soft baseline, same semantics as the dispatcher's
 * "never break the generation chain"): a missing or throwing externalizer, or
 * a null anchor, still yields the window — but the note says storage was
 * unavailable instead of pretending the full text is retrievable. Folding
 * never flips success/failure and never widens content.
 * <p>Estimation uses the cl100k default engine: this is a block-level budget
 * gate, not per-endpoint billing (the dialog-packet gate of keeps
 * the endpoint tokenizer where the send actually happens).
 */
@Component
public class ToolResultBudgetFolder {

    private static final Logger log = LoggerFactory.getLogger(ToolResultBudgetFolder.class);

    /** First head/tail character cap: ~4 chars per ASCII token, halved to fit. */
    private static final int CAP_START_FACTOR = 4;
    /** Shrink floor so a pathological CJK block still terminates. */
    private static final int CAP_FLOOR = 8;

    private final AiCtxConfigResolver resolver;
    private final ToolResultExternalizer externalizer;

    public ToolResultBudgetFolder(AiCtxConfigResolver resolver,
                                  Optional<ToolResultExternalizer> externalizer) {
        this.resolver = resolver;
        this.externalizer = externalizer.orElse(null);
    }

    /**
 * Bound one backfilled outcome against the tool-block budget.
 *
 * @param outcome raw dispatcher result (content may be null)
 * @param taskId generation task owner scope forwarded to the externalizer
 * @param projectId server-carried project segment forwarded to the externalizer
 * @return the same instance when within budget, a folded copy when beyond
 */
    public ToolCallOutcome bound(ToolCallOutcome outcome, Long taskId, Long projectId) {
        if (outcome == null || outcome.content() == null) {
            return outcome;
        }
        String folded = fold(outcome.content(), taskId, projectId);
        // Identity comparison is deliberate: fold returns the same instance when
        // within budget, so an untouched outcome rides through untouched.
        return folded == outcome.content() ? outcome
                : new ToolCallOutcome(outcome.toolName(), outcome.success(), folded);
    }

    /**
 * Budget-gate one raw text block (second consumer: the explore kernel's
 * built-in act observation, which backfills outside the dispatcher).
 * Returns the SAME instance when within budget; oversized content degrades
 * to the head/tail window with the retrieval-anchor pointer, exactly like
 * {@link #bound} — one folding implementation, no second copy.
 *
 * @param content raw block text, nullable (null rides through)
 * @param taskId generation task owner scope forwarded to the externalizer
 * @param projectId server-carried project segment forwarded to the externalizer
 * @return the verbatim content within budget, the folded window beyond it
 */
    public String fold(String content, Long taskId, Long projectId) {
        if (content == null) {
            return content;
        }
        TokenEstimator estimator = TokenEstimatorRegistry.resolve("cl100k", null);
        int budget = resolver.toolBlockMaxTokens();
        if (estimator.countTokens(content) <= budget) {
            return content;
        }
        String anchor = tryStore(content, taskId, projectId);
        return windowed(content, budget, anchor, estimator);
    }

    /** Externalize the verbatim full text; any incident degrades to null anchor. */
    private String tryStore(String fullText, Long taskId, Long projectId) {
        if (externalizer == null) {
            return null;
        }
        try {
            String anchor = externalizer.store(fullText, taskId, projectId);
            return anchor == null || anchor.isBlank() ? null : anchor;
        } catch (Exception e) {
            log.warn("工具全文落盘失败 taskId={} projectId={}: {}", taskId, projectId,
                    e.getMessage());
            return null;
        }
    }

    /** Halve the head/tail cap until the rendered block (pointer note included) fits. */
    private String windowed(String content, int budget, String anchor, TokenEstimator estimator) {
        int cap = Math.min(content.length(), Math.max(CAP_FLOOR, budget * CAP_START_FACTOR));
        String candidate;
        while (true) {
            candidate = render(content, cap, anchor);
            if (estimator.countTokens(candidate) <= budget || cap <= CAP_FLOOR) {
                return candidate;
            }
            cap = Math.max(CAP_FLOOR, cap / 2);
        }
    }

    private String render(String content, int cap, String anchor) {
        String head = content.substring(0, Math.min(cap, content.length()));
        String tail = content.length() <= cap ? "" : content.substring(content.length() - cap);
        String marker = anchor != null
                ? "【中间省略|全文已落盘 anchor=" + anchor + "|回答可用 retrieval 元工具携锚回取全文】"
                : "【中间省略|全文落盘暂不可用（存储通道缺席），本条仅保留头尾窗口】";
        return tail.isEmpty() ? head + marker : head + marker + tail;
    }
}
