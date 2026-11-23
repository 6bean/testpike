package com.sixbean.testpike.core.prompt.domain.component;

/**
 * Lifecycle codes of a prompt template version (single source for the
 * tp_prompt_template.status column, D4/; the DDL comment is the authority
 * these values mirror). Kept as one shared component because the template
 * service, the outcome rollout monitor and future consumers must not each
 * hardcode 1/2/3 and drift apart.
 */
public final class PromptTemplateStatus {

    /** Gray release (default for a new version; at most one per family is judged live). */
    public static final int GRAY = 1;
    /** Full release (a gray row is promoted to this; the family keeps at most one). */
    public static final int FULL = 2;
    /** Archived (the full row a promotion replaced). */
    public static final int HISTORY = 3;

    private PromptTemplateStatus() {
    }
}
