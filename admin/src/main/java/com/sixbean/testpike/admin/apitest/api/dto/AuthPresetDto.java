package com.sixbean.testpike.admin.apitest.api.dto;

import java.util.List;

/**
 * Signature auth preset served to the API editor (mirror of core AuthPresetInfo;
 * admin stays a thin bridge here — the catalogue logic lives in core).
 *
 * @param presetKey   preset identifier (config key suffix below apitest.auth.preset.)
 * @param label       human-readable name for the dropdown
 * @param assemblies  assembly rows pre-filled into the signature form
 */
public record AuthPresetDto(String presetKey, String label, List<AuthPresetAssemblyDto> assemblies) {
}
