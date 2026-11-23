package com.sixbean.testpike.core.credential.domain.component;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.credential.domain.service.CredentialService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


@Component
public class CredPlaceholderInjector {

    
    private static final Pattern CRED_REF = Pattern.compile("\\{\\{CRED:([A-Za-z_][A-Za-z0-9_]*)\\}\\}");

    /** Injected reference written as {@code @cred/key} (whole string, monitor LOGIN precedent). */
    private static final Pattern AT_CRED_REF =
            Pattern.compile("^@cred/([A-Za-z_][A-Za-z0-9_]*)$");

    private final CredentialService credentialService;

    public CredPlaceholderInjector(CredentialService credentialService) {
        this.credentialService = credentialService;
    }

    /**
     * Resolve credential references in an arbitrary tree (Map/List/String values).
     * <p>Used by the api-test envelope assembly point for the auth section: the
     * save gate (AuthFormContract) only checks the reference shape, so the vault
     * real value is injected here at dispatch — one dialect shared with the
     * mobile-step path below, never a second resolver. Returns a replaced copy;
     * the input tree is never mutated in place.
     */
    public Object injectValue(long projectId, Object value) {
        return replaceValue(projectId, value, new LinkedHashMap<>());
    }

    
    public List<Map<String, Object>> inject(long projectId, List<Map<String, Object>> steps) {
        if (steps == null || steps.isEmpty()) {
            return steps;
        }
        Map<String, String> resolvedCache = new LinkedHashMap<>();
        List<Map<String, Object>> out = new ArrayList<>(steps.size());
        for (Map<String, Object> step : steps) {
            out.add(replaceMap(projectId, step, resolvedCache));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Object replaceValue(long projectId, Object value, Map<String, String> cache) {
        if (value instanceof Map<?, ?> map) {
            return replaceMap(projectId, (Map<String, Object>) map, cache);
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(replaceValue(projectId, item, cache));
            }
            return out;
        }
        if (value instanceof String text) {
            return replaceString(projectId, text, cache);
        }
        return value;
    }

    private Map<String, Object> replaceMap(long projectId, Map<String, Object> source,
                                           Map<String, String> cache) {
        Map<String, Object> out = new LinkedHashMap<>(source.size());
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            out.put(entry.getKey(), replaceValue(projectId, entry.getValue(), cache));
        }
        return out;
    }

    private String replaceString(long projectId, String text, Map<String, String> cache) {
        // Whole-string @cred/key reference (monitor LOGIN dialect): the value IS the
        // reference, so the full string swaps to the vault secret — never a fragment
        // replace here (an embedded @cred/ inside template text is not a dialect the
        // save gate accepts for secret slots).
        Matcher at = AT_CRED_REF.matcher(text.trim());
        if (at.matches()) {
            return cache.computeIfAbsent(at.group(1),
                    k -> credentialService.resolveSecret(projectId, k));
        }
        Matcher m = CRED_REF.matcher(text);
        if (!m.find()) {
            return text;
        }
        StringBuilder sb = new StringBuilder();
        do {
            String key = m.group(1);
            String secret = cache.computeIfAbsent(key,
                    k -> credentialService.resolveSecret(projectId, k));
            m.appendReplacement(sb, Matcher.quoteReplacement(secret));
        } while (m.find());
        m.appendTail(sb);
        return sb.toString();
    }

    
    public static boolean containsCredReference(Object node) {
        if (node instanceof Map<?, ?> map) {
            return map.values().stream().anyMatch(CredPlaceholderInjector::containsCredReference);
        }
        if (node instanceof List<?> list) {
            return list.stream().anyMatch(CredPlaceholderInjector::containsCredReference);
        }
        return node instanceof String text && text.contains("{{CRED:");
    }

    
    public static java.util.Set<String> extractCredReferenceKeys(Object node) {
        java.util.Set<String> keys = new java.util.LinkedHashSet<>();
        collectCredKeys(node, keys);
        return keys;
    }

    private static void collectCredKeys(Object node, java.util.Set<String> keys) {
        if (node instanceof Map<?, ?> map) {
            map.values().forEach(v -> collectCredKeys(v, keys));
            return;
        }
        if (node instanceof List<?> list) {
            list.forEach(v -> collectCredKeys(v, keys));
            return;
        }
        if (node instanceof String text) {
            Matcher m = CRED_REF.matcher(text);
            while (m.find()) {
                keys.add(m.group(1));
            }
        }
    }

    /**
     * Save-gate companion of {@link #inject}: true when the whole string IS one vault
     * reference of either dialect — {@code {{CRED:key}}} alone or {@code @cred/key} alone.
     * Both patterns are the ones this component resolves, so a payload accepted by a save
     * gate can always be replaced at dispatch (no second grammar to keep in sync).
     * Embedded references inside longer template text are deliberately not accepted for a
     * secret slot: the slot value is the secret, not a sentence around it.
     */
    public static boolean isCredentialReference(String text) {
        if (text == null) {
            return false;
        }
        String trimmed = text.trim();
        return AT_CRED_REF.matcher(trimmed).matches()
                || CRED_REF.matcher(trimmed).matches();
    }

    /**
     * True when the text is written as a vault reference but in a shape the injector can
     * never resolve (dotted key, missing braces, nested fragment). A save gate uses this to
     * answer "your reference is malformed" instead of "you stored a plaintext secret".
     */
    public static boolean looksLikeCredentialReference(String text) {
        return text != null && (text.contains("{{CRED:") || text.trim().startsWith("@cred/"));
    }

    
    public void assertNoLeftover(List<Map<String, Object>> steps) {
        if (containsCredReference(steps)) {
            throw new ServiceException("步骤里仍残留凭据引用未替换——凭据实值应由派发前服务端"
                    + "注入，请检查任务装配配置，或把用例步骤里的引用键改成正确写法 "
                    + "{{CRED:键名}}（键须已在「APP 自动化 → 凭据保险箱」建档）后重试",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    /**
     * Counts references to one key inside a flat text using the SAME grammar as
     * injection (red line: no second regex). The credential "test now" door
     * reconciles URL + header + body surfaces against one number .
     */
    public static int countReferencesTo(String text, String credKey) {
        if (text == null || credKey == null || credKey.isBlank()) {
            return 0;
        }
        int count = 0;
        Matcher m = CRED_REF.matcher(text);
        while (m.find()) {
            if (m.group(1).equals(credKey)) {
                count++;
            }
        }
        return count;
    }
}
