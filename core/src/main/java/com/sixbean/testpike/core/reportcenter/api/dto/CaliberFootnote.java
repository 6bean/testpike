package com.sixbean.testpike.core.reportcenter.api.dto;

/**
 * Footnote line quoting one registered caliber (code + display name + version).
 *
 * <p>Generated from the caliber registry itself (single source) so dashboard
 * footnotes can never drift from the definitions the server actually runs.
 */
public record CaliberFootnote(String code, String displayName, String version) {
}
