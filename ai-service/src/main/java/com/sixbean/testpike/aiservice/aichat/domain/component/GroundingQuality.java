package com.sixbean.testpike.aiservice.aichat.domain.component;

/**
 * Material quality grade of a grounded answer's evidence pool (AX batch
 * item 6). The kernel used to say only "found something" or "found
 * nothing"; the four grounded grades sharpen what the answer may promise:
 * <ul>
 * <li>{@link #HIGH} — evidence clears the strong bar, sources agree,
 * nothing looks stale: answer straight from the material;</li>
 * <li>{@link #WEAK} — only above the bare relevance floor: the answer may
 * lean on the material but must stay visibly cautious;</li>
 * <li>{@link #CONFLICT} — two sources assert different values for the same
 * field: the answer must name the disagreement instead of picking silently
 * (leads the grade over staleness because it is the sharper truth claim);</li>
 * <li>{@link #STALE_SUSPECT} — a material's update predates the
 * staleness window: the answer must flag it as possibly behind recent
 * changes;</li>
 * <li>{@link #NONE} — the honest zero-hit pool (no citations, no block).</li>
 * </ul>
 * Labels ride Chinese because the naming lines these grades produce are
 * relayed into user-visible answers.
 */
public enum GroundingQuality {

    /** Nothing relevant found — honest refusal shape. */
    NONE("无可用材料"),
    /** Strong evidence, consistent, fresh. */
    HIGH("高相关"),
    /** Above the floor but thin — caution wording required. */
    WEAK("弱相关"),
    /** Same field, mutually exclusive values across sources. */
    CONFLICT("相互矛盾"),
    /** A material predates the staleness window. */
    STALE_SUSPECT("过期嫌疑");

    private final String label;

    GroundingQuality(String label) {
        this.label = label;
    }

    /** Human-readable grade name used inside the naming lines. */
    public String label() {
        return label;
    }
}
