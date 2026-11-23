package com.sixbean.testpike.aiservice.aichat.domain.component;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.common.config.FactoryOnSwitch;

/**
 * Behaviour-directive resolution for one dialog turn .
 * <p>The system head used to be a two-sentence built-in constant, so the
 * model's working posture (verify-before-answering, step plans, disambiguate
 * only when it is expensive to guess wrong, self-check and offer next steps)
 * could never be tuned without a redeploy. This piece moves that instruction
 * set into the published {@code chat-behavior} prompt-template family — the
 * same versioned/gray/rollback governance the generation chain already uses —
 * while the constant stays as the conservative fallback.
 * <p>Tri-state gate semantics (K8, factory default on): only an explicit
 * 0/false closes the directives; a missing row or an unparsable value keeps
 * them on, because a silently-switched-off AI behaviour would be invisible to
 * the operator. The template row is read fresh every turn (no cache) so an
 * edit on the management page lands on the very next turn — the
 * PromptTemplateResolver "read per use" discipline.
 * <p>Pure resolution here (no I/O): the caller owns the gateway reads and
 * must treat a read failure the same as an absent row — the turn always gets
 * a non-empty head, the chain never breaks silently.
 */
public final class ChatBehaviorDirectives {

    /** Factory-on governance key for the directive injection. */
    public static final String KEY_ENABLED = "ai.chat.behavior-directives.enabled";

    /** Template family consumed by the dialog assembly (tp_prompt_template name). */
    public static final String TEMPLATE_NAME = "chat-behavior-directives";

    /** Template business domain of this family (tp_prompt_template domain). */
    public static final String TEMPLATE_DOMAIN = "chat-behavior";

    
    public static final int PUBLISHED_STATUS = 2;

    private ChatBehaviorDirectives() {
    }

    /**
     * Tri-state switch read: absent row / blank / unparsable keep the
     * directives on; only an explicit 0 or false closes them.
     * <p>Delegates to the shared {@link FactoryOnSwitch} (R40 single
     * source): the core dispatch pre-check reads door states through the
     * same semantics, so producer and consumer faces cannot diverge.
     */
    public static boolean switchOn(String rawValue) {
        return FactoryOnSwitch.on(rawValue);
    }

    /**
     * The system head for one turn: the published template content when the
     * family carries usable text, otherwise the caller's conservative
     * fallback — a missing, null-content or whitespace-only row never yields
     * an empty head.
     */
    public static String headOrFallback(CoreGateway.PromptTemplateBrief publishedRow,
                                        String fallback) {
        if (publishedRow == null || publishedRow.content() == null
                || publishedRow.content().isBlank()) {
            return fallback;
        }
        return publishedRow.content();
    }
}
