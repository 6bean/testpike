package com.sixbean.testpike.aiservice.llm.pipeline;

import com.sixbean.testpike.aiservice.llm.domain.component.SysConfigBooleans;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * single source for reading a per-domain exhaustive-recall switch
 * and turning it into the prompt segment it controls. shipped the
 * clause block ({@link ExhaustiveRecallDirectives}) plus one reader inside
 * the requirement-extraction service; gives four more enumeration
 * faces the same clause, and five copies of the read-and-degrade sequence
 * would be exactly the drift the R40 single-source rule forbids.
 *
 * <p>Read posture (identical for every domain): the switch ships ON — a
 * missing, blank or illegal value stays on with a warn naming the key, so
 * an operator's typo can never silently drop the recall instruction; only
 * the honest off spellings ("false"/"0") turn it off. Values are judged by
 * shape through {@link SysConfigBooleans}, never by spelling.
 */
public final class RecallDirectiveSwitch {

    private static final Logger LOG = LoggerFactory.getLogger(RecallDirectiveSwitch.class);

    private RecallDirectiveSwitch() {
    }

    /**
     * The prompt segment a face should append: the shared clause block when
     * the raw config value reads on (or falls back to on), zero bytes when
     * it reads off. Illegal non-blank values warn naming the key before
     * the fail-soft ON fallback.
     */
    public static String segment(String rawValue, String key) {
        if (rawValue != null && !rawValue.isBlank() && !SysConfigBooleans.isLegal(rawValue)) {
            LOG.warn("K8 {}={} invalid (allowed: true/false), treated as shipped default enabled", key, rawValue);
        }
        return ExhaustiveRecallDirectives.appendTo("", SysConfigBooleans.parse(rawValue, true));
    }
}
