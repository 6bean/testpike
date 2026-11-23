package com.sixbean.testpike.api.mock.api.dto;

import java.util.List;

/**
 * Dialect export payload returned by GET /open/mock/ci/spaces/{spaceId}/export
 * (api mirror of core ExportDialectResult).
 * <p>{@code notes} is the honesty loss-list: every rule feature the target
 * dialect cannot carry is named per stub. The door passes it through intact —
 * a CI migration that silently loses fidelity is worse than one that admits it.
 *
 * @param format  dialect id echoed by core (mockoon | wiremock)
 * @param content file body (JSON text)
 * @param notes   per-stub honesty notes (empty when the mapping is lossless)
 */
public record MockCiExportView(String format, String content, List<String> notes) {
}
