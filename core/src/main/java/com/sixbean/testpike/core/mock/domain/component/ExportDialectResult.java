package com.sixbean.testpike.core.mock.domain.component;

import java.util.List;

/**
 * Outcome of one dialect render .
 * <p>{@code content} is the file body; {@code notes} is the honest loss list —
 * every rule feature that the target dialect cannot carry natively is named
 * per stub instead of being silently dropped. Consumers show the notes next
 * to the download so a migrated mock never pretends fidelity it lacks.
 *
 * @param format dialect id (mockoon | wiremock)
 * @param content file body (JSON text)
 * @param notes per-stub honesty notes (empty when the mapping is lossless)
 */
public record ExportDialectResult(String format, String content, List<String> notes) {
}
