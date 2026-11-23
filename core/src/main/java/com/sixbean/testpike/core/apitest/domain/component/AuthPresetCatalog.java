package com.sixbean.testpike.core.apitest.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.apitest.api.dto.AuthPresetAssembly;
import com.sixbean.testpike.core.apitest.api.dto.AuthPresetInfo;
import com.sixbean.testpike.core.config.api.dto.ConfigInfo;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Signature auth preset catalogue (api-test W1c, data-driven K8 surface).
 * <p>Presets live as tp_sys_config rows under the {@code apitest.auth.preset.}
 * config row — no code change, hot-updated on the next read (red line: thresholds
 * and catalogues stay configurable). The catalogue reads through
 * {@link ConfigService#list(String)} (no second query path) and filters by prefix.
 * <p>A malformed row is dropped honestly (logged + counted, named in the log by
 * its config key only) instead of hiding the whole catalogue behind one bad seed.
 * Values are template text — the ${gen.*} dialect is resolved by the executor's
 * single parser and {{CRED:key}} references by the dispatch-time injector; this
 * component never evaluates either (no second resolver).
 */
@Component
public class AuthPresetCatalog {

    /** Config-key prefix every signature preset lives under. */
    public static final String PRESET_KEY_PREFIX = "apitest.auth.preset.";

    /** Config group hosting the preset rows (live-verified group name). */
    public static final String PRESET_GROUP = "API测试";

    private static final Logger log = LoggerFactory.getLogger(AuthPresetCatalog.class);

    private final ConfigService configService;
    private final ObjectMapper objectMapper;

    public AuthPresetCatalog(ConfigService configService, ObjectMapper objectMapper) {
        this.configService = configService;
        this.objectMapper = objectMapper;
    }

    /**
     * All valid presets, ordered by config id (insertion order = stable listing).
     * <p>Rows outside the prefix, non-JSON values or shapes failing the structural
     * check are dropped with a logged name — the caller never sees a half-broken
     * row (an unusable preset in the editor dropdown would fail at save time with
     * a worse message than "the catalogue does not offer it").
     */
    public List<AuthPresetInfo> presets() {
        List<AuthPresetInfo> out = new ArrayList<>();
        for (ConfigInfo row : configService.list(PRESET_GROUP)) {
            String key = row.configKey();
            if (key == null || !key.startsWith(PRESET_KEY_PREFIX)) {
                continue;
            }
            AuthPresetInfo parsed = parse(row, key);
            if (parsed != null) {
                out.add(parsed);
            }
        }
        return List.copyOf(out);
    }

    private AuthPresetInfo parse(ConfigInfo row, String key) {
        String suffix = key.substring(PRESET_KEY_PREFIX.length());
        try {
            JsonNode value = objectMapper.readTree(row.configValue());
            String label = value.path("label").asText("").trim();
            JsonNode assemblies = value.get("assemblies");
            if (label.isEmpty() || assemblies == null || !assemblies.isArray()
                    || assemblies.isEmpty()) {
                throw new IllegalArgumentException("label or assemblies missing");
            }
            List<AuthPresetAssembly> rows = new ArrayList<>();
            for (JsonNode item : assemblies) {
                String name = item.path("name").asText("").trim();
                String valueText = item.path("value").asText("");
                String into = item.path("into").asText("header").trim()
                        .toLowerCase(java.util.Locale.ROOT);
                if (name.isEmpty() || valueText.isBlank()
                        || !(AuthFormContract.PLACEMENTS.contains(into))) {
                    throw new IllegalArgumentException("assembly row incomplete");
                }
                rows.add(new AuthPresetAssembly(into, name, valueText));
            }
            return new AuthPresetInfo(suffix, label, rows);
        } catch (Exception e) {
            // Honest drop: name the broken key (config text contains templates and
            // {{CRED:}} references only — never secret values, but log no payload).
            log.warn("签名认证预设配置行解析失败已跳过 key={} 原因={}", key, e.getMessage());
            return null;
        }
    }
}
