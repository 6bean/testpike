package com.sixbean.testpike.aiservice.llm.pipeline;

import com.sixbean.testpike.aiservice.aichat.domain.component.ChatBehaviorDirectives;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * L0 behaviour-kernel assembly for the reasoning pipeline .
 * <p>All reasoning domains share one clause-assembly source instead of each
 * gluing its own prompt head: the domain head comes from the published
 * behaviour-directive template family through the existing directive piece
 * (single-sourced tri-state gate and honest-degradation fallback, no copy of
 * that logic here), and every assembled head carries the failure-transparency
 * and evidence-first invariant appended exactly once. The invariant is the
 * kernel's non-negotiable floor: a run that silently drops half its work or
 * states invented numbers must not pass as complete, whichever domain calls
 * the model.
 * <p>Pure resolution (no I/O): the caller owns the gateway reads and treats a
 * read failure the same as an absent row, so a template outage degrades the
 * head to the conservative fallback but never empties it and never takes the
 * reasoning run down.
 */
public final class BehaviorKernelAssembly {

    /**
     * Invariant clause carried by every assembled head (failure transparency
     * + evidence-first): partial failures are reported, unsupported judgements
     * are marked as assumptions, and figures come from real reads only.
     */
    public static final String KERNEL_CLAUSE =
            "执行纪律：任何一步失败或被跳过，必须如实说明该步骤、原因与影响范围，"
                    + "不得静默略过未完成的部分而按全部完成作答；没有证据支撑的判断要标注为假设。"
                    + "结论中的数量、状态与计数必须来自实际查询或工具返回，不得凭印象给出。";

    private static final Logger LOG = LoggerFactory.getLogger(BehaviorKernelAssembly.class);

    private BehaviorKernelAssembly() {
    }

    /**
     * Tri-state gate read shared by all pipeline consumers; the semantics are
     * owned by the directive piece so a gate rule can never drift per domain.
     */
    public static boolean directivesEnabled(String rawSwitchValue) {
        return ChatBehaviorDirectives.switchOn(rawSwitchValue);
    }

    /**
     * The system head for one reasoning call: the published template row when
     * it carries usable text, otherwise the caller's conservative fallback —
     * extended with the kernel invariant either way, never empty.
     */
    public static String systemHead(CoreGateway.PromptTemplateBrief publishedRow,
                                    String conservativeFallback) {
        return prefixKernelClause(ChatBehaviorDirectives.headOrFallback(
                publishedRow, conservativeFallback));
    }

    /**
     * Places the invariant clause at the very front of the head exactly once;
     * a head that already carries it (a re-assembled or pre-extended text) is
     * returned untouched. The clause is byte-identical on every call of every
     * reasoning domain, so leading with it hands the provider prompt cache a
     * stable shared prefix to hit (prefix economics): the domain-specific
     * template text always follows, never precedes.
     */
    public static String prefixKernelClause(String head) {
        if (head == null || head.isBlank()) {
            return KERNEL_CLAUSE;
        }
        if (head.contains(KERNEL_CLAUSE)) {
            return head;
        }
        return KERNEL_CLAUSE + "\n\n" + head;
    }

    /**
 * The one head-assembly step shared by every upgraded domain ,
 * R40: the classify/locate/spec domains were repeating the same gate read →
 * published-row read → degrade-to-contract sequence, so it lives here now):
 * governance tri-state gate first (closed = zero template reads), the
 * published row of the family when open, and the conservative contract
 * either way — always extended with the kernel invariant exactly once.
 * A failing read degrades to the contract and never takes the reasoning
 * run down; the degradation is warned, never silent.
 */
    public static String assembleHead(String governanceRawValue,
                                      CoreGateway.PromptTemplateBrief publishedRow,
                                      String conservativeContract) {
        String head = directivesEnabled(governanceRawValue)
                ? systemHead(publishedRow, conservativeContract)
                : prefixKernelClause(conservativeContract);
        return head.strip();
    }

    /**
     * Assembly wrapper owning the read order and the degrade path: the supplier
     * is only consulted while the governance gate is open (closed = the template
     * endpoint is never touched), and a failing supplier read lands on the
     * conservative contract with the invariant appended once.
     */
    public static String assembleHead(String governanceRawValue,
                                      Supplier<CoreGateway.PromptTemplateBrief> publishedRowRead,
                                      String conservativeContract,
                                      String degradeLogPrefix) {
        try {
            CoreGateway.PromptTemplateBrief publishedRow = directivesEnabled(governanceRawValue)
                    ? publishedRowRead.get() : null;
            return assembleHead(governanceRawValue, publishedRow, conservativeContract);
        } catch (RuntimeException e) {
            LOG.warn("{} (not fatal, falling back to the built-in contract): {}", degradeLogPrefix, e.toString());
            return prefixKernelClause(conservativeContract).strip();
        }
    }

    /**
     * Read-side wrapper for upgraded domains: owns the CoreGateway read
     * sequence (governance gate → published row of the family while the gate
     * is open) so a newly upgraded domain delegates in a single line instead
     * of repeating the gate/read/degrade boilerplate (R40 — extracted when
     * the boilerplate found its fourth consumer). A failing read lands on
     * {@link #assembleHead(String, Supplier, String, String)}'s degrade path
     * and is warned under the domain's own prefix.
     */
    public static String assembleHeadFromGateway(CoreGateway gateway,
                                                 String governanceKey,
                                                 String templateName,
                                                 String templateDomain,
                                                 String conservativeContract,
                                                 String degradeLogPrefix) {
        return assembleHead(gateway.getSysConfig(governanceKey),
                () -> gateway.promptTemplateLatestByStatus(templateName,
                        templateDomain, ChatBehaviorDirectives.PUBLISHED_STATUS),
                conservativeContract, degradeLogPrefix);
    }

    /**
     * Counts occurrences of the invariant clause in an assembled head (shared
     * assertion helper for upgraded-domain tests — "exactly once" is the kernel
     * contract, so the counter must not be copy-pasted per test class).
     */
    public static int kernelClauseCount(String text) {
        int count = 0;
        for (int i = text.indexOf(KERNEL_CLAUSE); i >= 0;
             i = text.indexOf(KERNEL_CLAUSE, i + KERNEL_CLAUSE.length())) {
            count++;
        }
        return count;
    }
}
