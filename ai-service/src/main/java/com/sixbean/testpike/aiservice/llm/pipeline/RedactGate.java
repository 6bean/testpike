package com.sixbean.testpike.aiservice.llm.pipeline;

import com.sixbean.testpike.aiservice.aichat.domain.component.ChatBehaviorDirectives;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.pipeline.DenialGuidanceFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pre-send reasoning-input redaction gate (behaviour kernel second round,
 * item 2): every reasoning payload leaves the system through this one
 * masking source. Credential / identity word forms detected in the outbound
 * text are replaced by labelled mask markers before the provider ever sees
 * them — stored originals are untouched, only the send payload is
 * <p>Three reasoning surfaces are wired to this piece: the dialog free-text
 * prompt, the image-observation blocks, and the generation-domain
 * built-in forms only fire on unambiguous credential/identity shapes, and
 * boundary samples are drained through the golden set, so ordinary
 * requirement text passes byte-identical.
 * <p>The gate switch follows the shared tri-state semantics (single source:
 * {@link ChatBehaviorDirectives#switchOn}) — a missing or blank row is
 * open, only an explicit 0/false closes. A closed gate does NOT silently
 * raw-send detected sensitive text: the send is refused with the unified
 * switched-off guidance frame naming the settings path and the config key
 * (R32), while a clean payload passes untouched.
 * <p>Pure resolution — the caller owns the two config reads (hot per send,
 * no caching) and passes the raw values in, same posture as the other L0
 * pipeline pieces.
 */
public final class RedactGate {

    /** Factory-on master switch for the redaction gate. */
    public static final String KEY_ENABLED = "ai.llm.redact-enabled";

    /**
 * Governance key for the word-form set: one extra form per line as
 * are skipped (never takes the send path down). Hot read per send.
 */
    public static final String KEY_PATTERNS = "ai.llm.redact.patterns";

    /** Human label of the gated feature, used by the switched-off frame. */
    public static final String FEATURE_NAME = "推理输入脱敏";

    /** Prefix shared by every mask marker (asserted by surface wiring). */
    public static final String MASK_MARK = "【已脱敏";

    private static final Logger LOG = LoggerFactory.getLogger(RedactGate.class);

    /**
     * Built-in conservative forms. Order matters: the identity-card rule
     * runs before the mobile rule so the 18-digit span masks as one form;
     * lookarounds keep either rule from cutting into longer digit runs.
     */
    private static final List<RedactRule> BUILTIN_FORMS = List.of(
            new RedactRule("API密钥",
                    Pattern.compile("(?i)\\bsk-[A-Za-z0-9][A-Za-z0-9_\\-]{15,}")),
            new RedactRule("云访问密钥",
                    Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b")),
            new RedactRule("密钥赋值",
                    Pattern.compile("(?i)(?:password|passwd|pwd|secret|token|api[_ -]?key"
                            + "|access[_ -]?key|密码|口令|密钥)\\s*[=:：]\\s*[\\w\\-./+=@]{8,}")),
            new RedactRule("身份证号",
                    Pattern.compile("(?<!\\d)\\d{6}(?:19|20)\\d{2}(?:0[1-9]|1[0-2])"
                            + "(?:0[1-9]|[12]\\d|3[01])\\d{3}[\\dXx](?!\\d)")),
            new RedactRule("手机号",
                    Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)")));

    private RedactGate() {
    }

    /**
     * The shared tri-state read (semantics owned by the directive piece so
     * no switch family can drift its gate rule): missing/blank/unknown =
     * open, explicit 0/false = closed.
     */
    public static boolean gateOpen(String rawSwitchValue) {
        return ChatBehaviorDirectives.switchOn(rawSwitchValue);
    }

    /**
     * The one pre-send transform every reasoning surface calls before its
     * payload leaves the system. Gate open (the shipped default) → returns
     * the masked payload. Gate closed → a clean payload passes raw, while a
     * payload still carrying a sensitive form is refused with the unified
     * guidance frame and zero model calls.
     */
    public static String redactOutbound(String rawSwitchValue, String rawCustomForms,
                                        String payload) {
        if (payload == null || payload.isEmpty()) {
            return payload;
        }
        if (gateOpen(rawSwitchValue)) {
            return mask(rawCustomForms, payload);
        }
        List<String> hits = detect(rawCustomForms, payload);
        if (!hits.isEmpty()) {
            LOG.warn("推理输入脱敏已关闭但检出敏感形态 {}，本轮拒发（零模型调用）", hits);
            throw new ServiceException(
                    DenialGuidanceFrame.switchClosed(FEATURE_NAME, KEY_ENABLED),
                    BizErrorCode.FORBIDDEN);
        }
        return payload;
    }

    /** Replaces every detected form span with its labelled mask marker. */
    public static String mask(String rawCustomForms, String text) {
        String masked = text;
        int hits = 0;
        for (RedactRule rule : rules(rawCustomForms)) {
            Matcher matcher = rule.pattern().matcher(masked);
            if (!matcher.find()) {
                continue;
            }
            StringBuilder rebuilt = new StringBuilder();
            do {
                matcher.appendReplacement(rebuilt,
                        Matcher.quoteReplacement(maskMarker(rule.form())));
                hits++;
            } while (matcher.find());
            matcher.appendTail(rebuilt);
            masked = rebuilt.toString();
        }
        if (hits > 0) {
            // Counts only — the payload (and anything it carried) never
            // enters the log line.
            LOG.info("推理输入发送前脱敏：遮蔽 {} 处敏感形态", hits);
        }
        return masked;
    }

    /** Which forms are present in the text (names only, never values). */
    public static List<String> detect(String rawCustomForms, String text) {
        List<String> found = new ArrayList<>();
        for (RedactRule rule : rules(rawCustomForms)) {
            if (rule.pattern().matcher(text).find()) {
                found.add(rule.form());
            }
        }
        return found;
    }

    /**
 * Effective vocabulary: the built-in set plus hot governance lines
 * a broken governance edit must never take the send path down, and it
 * must never silently widen into "no masking" either, so the built-in
 * forms stay in force regardless.
 */
    private static List<RedactRule> rules(String rawCustomForms) {
        if (rawCustomForms == null || rawCustomForms.isBlank()) {
            return BUILTIN_FORMS;
        }
        List<RedactRule> merged = new ArrayList<>(BUILTIN_FORMS);
        for (String line : rawCustomForms.lines().toList()) {
            String entry = line.strip();
            if (entry.isEmpty()) {
                continue;
            }
            int split = entry.indexOf('=');
            if (split < 1 || split == entry.length() - 1) {
                LOG.warn("脱敏词形治理键行格式无法解析（需 形态名=正则），本行忽略: {}", entry);
                continue;
            }
            try {
                merged.add(new RedactRule(entry.substring(0, split).strip(),
                        Pattern.compile(entry.substring(split + 1).strip())));
            } catch (RuntimeException badRegex) {
                LOG.warn("脱敏词形治理键正则不可编译，本行忽略: {} ({})", entry,
                        badRegex.getMessage());
            }
        }
        return merged;
    }

    private static String maskMarker(String form) {
        return MASK_MARK + "：" + form + "】";
    }
}
