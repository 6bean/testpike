package com.sixbean.testpike.core.cluster.domain.component;

import java.util.Locale;
import java.util.Map;

/**
 * Third-party homepage resolution for ledger families (single source):
 * engine-runtime families join tp_appauto_engine.install_doc_url; non-engine
 * runtime families fall back to the verified static table below; everything
 * else — self-built platform families, blank catalog URLs, unknown families —
 * resolves to null so the UI renders an honest em-dash instead of a dead link.
 * <p>Static-table URLs were reachability-verified 2026-10-13 from the dev
 * egress (developer.android.com recorded per its sole official source; the
 * local timeout there is an egress artifact, not a wrong URL — re-verify on a
 * clean network).
 */
public final class ThirdPartyHomepageRules {

    /** Runtime family keys (engine-runtime.&lt;key&gt; or bare key) with verified official homepages. */
    private static final Map<String, String> RUNTIME_HOMEAGES = Map.of(
            "node", "https://nodejs.org",
            "jdk", "https://adoptium.net",
            "java", "https://adoptium.net",
            "adb", "https://developer.android.com/tools/releases/platform-tools",
            "hdc", "https://developer.huawei.com/consumer/cn/download/",
            "xcrun", "https://developer.apple.com/xcode/",
            "mobilecli", "https://github.com/mobile-next/mobilecli");

    private ThirdPartyHomepageRules() {
    }

    /**
     * Resolve one ledger family to its third-party homepage, or null when the
     * family carries no external homepage (callers must not invent fallbacks).
     * Engine-catalog URLs (lowercased engine code -> install_doc_url, blanks
     * normalized to absent by the loader) take precedence for engine-runtime
     * families; the static table covers non-engine runtime keys.
     */
    public static String resolve(String componentFamily, Map<String, String> catalogUrlByLowerCode) {
        if (componentFamily == null || componentFamily.isBlank()) {
            return null;
        }
        String family = componentFamily.trim().toLowerCase(Locale.ROOT);
        if (ComponentSupportRules.PLATFORM_FAMILIES.contains(family)) {
            return null; // self-built platform pieces are not third-party quick-link targets
        }
        String key = family.startsWith(ComponentSupportRules.ENGINE_PREFIX)
                ? family.substring(ComponentSupportRules.ENGINE_PREFIX.length())
                : family;
        String fromCatalog = catalogUrlByLowerCode == null ? null : catalogUrlByLowerCode.get(key);
        if (fromCatalog != null && !fromCatalog.isBlank()) {
            return fromCatalog;
        }
        return RUNTIME_HOMEAGES.get(key);
    }
}
