package com.sixbean.testpike.core.apitest.api.dto;

import java.util.List;

/**
 * A signature auth preset served by the catalogue endpoint (api-test W1c).
 * <p>Presets are K8 config rows under the apitest.auth.preset. prefix, so the
 * list grows by configuration alone. {@code presetKey} is the key suffix
 * (e.g. hmac-sha256) — the stable identifier the editor pre-fill targets.
 *
 * @param presetKey   key suffix below the preset prefix
 * @param label       human-readable preset name for the editor dropdown
 * @param assemblies  assembly rows pre-filled into the signature form
 */
public record AuthPresetInfo(String presetKey, String label, List<AuthPresetAssembly> assemblies) {
}
