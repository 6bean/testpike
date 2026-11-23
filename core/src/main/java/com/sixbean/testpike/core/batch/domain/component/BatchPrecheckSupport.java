package com.sixbean.testpike.core.batch.domain.component;

import com.sixbean.testpike.common.util.TlsMode;
import com.sixbean.testpike.core.plans.api.dto.SkipItem;

import java.util.ArrayList;
import java.util.List;

/**
 * This common component handles batch-start preflight check classification, including status validation, flaky isolation skip, and human-readable skip phrases.
 * This is extracted from PlanServiceImpl.generateBatches; plan-generated batches and the user-facing /batches/{id}/start share the same source.
 * A red line prohibits copy-pasted duplicate logic.
 * <p>The extended semantics are as follows:
 * <ul>
 * <li>When flaky=1, skip unconditionally before status evaluation with reason='isolated (unstable)'.</li>
 * <li>When status≠3 (not review-approved), apply the testpike.batch.unevaluated-mode setting: skip=skip.
 * The reason is 'status mismatch', or block mode rejects the entire start (only this case is blocked; flaky never blocks in any mode).</li>
 * <li>When status=3 and not isolated, enter the execution queue.</li>
 * </ul>
 *
 */
public final class BatchPrecheckSupport {

    /** This is the configuration key for the handling mode of not-review-approved cases (skip default / block). */
    public static final String CFG_UNEVALUATED_MODE = "testpike.batch.unevaluated-mode";
    /** This is the configuration key that forbids insecure execution in exit plans (1/true=enabled; default/0/invalid=disabled). */
    public static final String CFG_REJECT_INSECURE_TLS = "tls.exit-plan.reject-insecure";
    /** This denotes case status=review-approved according to the state machine. */
    public static final int CASE_APPROVED = 3;
    /** This is the reason for flaky isolation skip (same wording as the plan-generated batch list; frontend direct access and report wording share the same source). */
    public static final String FLAKY_SKIP_REASON = "已隔离（不稳定）";

    private BatchPrecheckSupport() {
    }

    /**
     * This classifies each case: flaky first, then review-approved, then status mismatch.
     *
     * @param rows denotes preflight decision rows (loaded in batch by the caller; N+1 prohibited).
     * @param mode denotes the unevaluated-mode configuration value (null is treated as skip).
     */
    public static BatchPrecheckResult classify(List<BatchPrecheckRow> rows, String mode) {
        List<Long> approved = new ArrayList<>();
        List<SkipItem> skips = new ArrayList<>();
        for (BatchPrecheckRow r : rows) {
            if (r.flaky() == 1) {
                skips.add(new SkipItem(r.caseId(), r.caseNo(), r.title(), r.status(),
                        FLAKY_SKIP_REASON));
            } else if (r.status() == CASE_APPROVED) {
                approved.add(r.caseId());
            } else {
                skips.add(new SkipItem(r.caseId(), r.caseNo(), r.title(), r.status()));
            }
        }
        List<SkipItem> unevaluated = skips.stream()
                .filter(s -> !FLAKY_SKIP_REASON.equals(s.reason())).toList();
        List<SkipItem> flaky = skips.stream()
                .filter(s -> FLAKY_SKIP_REASON.equals(s.reason())).toList();
        boolean blocked = !unevaluated.isEmpty() && "block".equalsIgnoreCase(mode);
        return new BatchPrecheckResult(approved, skips, unevaluated, flaky, blocked);
    }

    /**
     * This builds the human-readable phrase for the skip list (message concatenation; status mismatch and flaky isolation share the same logic).
     *
     * @param items denotes skipped items of this category (nullable).
     * @param label denotes the human-readable category (for example, 'not-review-approved cases').
     * @return returns ', skipped N {label} items (title1, title2, ...)' or an empty string when nothing is skipped.
     */
    public static String skipPhrase(List<SkipItem> items, String label) {
        if (items == null || items.isEmpty()) {
            return "";
        }
        String titles = items.stream().limit(3).map(SkipItem::title)
                .collect(java.util.stream.Collectors.joining("」「"));
        return "，跳过 " + items.size() + " 条" + label + "（「" + titles
                + (items.size() > 3 ? "」等" : "」") + "）";
    }

    /** This is the human-readable message for rejecting start in block mode (name the first 3 titles). */
    public static String blockMessage(List<SkipItem> unevaluatedSkips) {
        String titles = unevaluatedSkips.stream().limit(3)
                .map(SkipItem::title).collect(java.util.stream.Collectors.joining("」「"));
        return "存在 " + unevaluatedSkips.size() + " 条未通过评审用例（「" + titles
                + (unevaluatedSkips.size() > 3 ? "」等" : "」")
                + "），已配置为阻止模式，请先完成用例评审或在系统配置调整未通过评审用例处理模式";
    }

    /**
     * This is the decision that forbids insecure execution in exit plans.
     * <p>Enabled values are 1/true (case-insensitive and trimmed); null/0/invalid values are disabled (zero drift for existing data).
     * This follows the same fail-safe mindset as certificate warning windows: broken configuration never silently enables blocking and never silently relaxes it.
     * This blocks only the insecure tier (normalized via {@link TlsMode#fromRaw}; invalid tier values are conservatively normalized to strict and not blocked).
     *
     * @param configValue denotes the tls.exit-plan.reject-insecure configuration value (null means the key is absent).
     * @param envTlsMode denotes the raw TLS tier value for the batch execution environment (mode in the tls section of config_json).
     */
    public static boolean rejectInsecureTls(String configValue, String envTlsMode) {
        if (configValue == null) {
            return false;
        }
        String v = configValue.trim();
        boolean enabled = "1".equals(v) || "true".equalsIgnoreCase(v);
        return enabled && TlsMode.fromRaw(envTlsMode) == TlsMode.INSECURE;
    }

    /** This is the human-readable message for insecure rejection (name the environment, tier source, and configuration to change; avoid throwing raw jargon). */
    public static String insecureBlockMessage(String envName) {
        return "执行环境「" + envName + "」配置为跳过证书校验（insecure）模式，"
                + "当前已开启「准出计划禁含跳过证书校验的执行」，该批次不能启动；"
                + "请为该环境配置可信 CA（custom_ca）或改用 strict 档，"
                + "或在系统配置 tls.exit-plan.reject-insecure 调整该开关";
    }

    /**
     * Exit-plan mock ban config key (honesty chain D2, ruling M-f/U-c): 1/true =
     * on, absent/0/illegal = off so existing batches see zero drift. Seeded to 1
     * in V228 — simulated traffic must never masquerade as real-integration exit
     * evidence.
     */
    public static final String CFG_MOCK_EXIT_PLAN_FORBID = "mock.exit-plan.forbid";

    /**
     * Decide whether a batch launch is blocked by the exit-plan mock ban.
     * Enabled values are exactly 1/true (case/whitespace tolerant); anything
     * else (null/0/illegal) keeps the ban off — a broken config never silently
     * widens the gate nor silently opens it (same fail-safe family as the
     * insecure-TLS gate above).
     *
     * @param configValue   mock.exit-plan.forbid raw value (nullable = key absent)
     * @param mockBoundCount live batch members that declare a mock space (0 = no mock usage)
     */
    public static boolean rejectMockBoundCases(String configValue, int mockBoundCount) {
        if (configValue == null) {
            return false;
        }
        String v = configValue.trim();
        boolean enabled = "1".equals(v) || "true".equalsIgnoreCase(v);
        return enabled && mockBoundCount > 0;
    }

    /** Plain-word rejection message: names the count, the reason and the config key. */
    public static String mockBoundBlockMessage(int mockBoundCount) {
        return "批次内有 " + mockBoundCount + " 条用例声明了 Mock 仿真空间，"
                + "当前已开启「准出计划禁含 Mock 用例」，仿真通过不得充当真实链路准出证据，该批次不能启动；"
                + "请在真实集成环境另跑（解除用例的 Mock 绑定），"
                + "或在系统配置 mock.exit-plan.forbid 调整该开关";
    }
}
