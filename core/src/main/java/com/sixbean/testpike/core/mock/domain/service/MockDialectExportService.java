package com.sixbean.testpike.core.mock.domain.service;

import com.sixbean.testpike.core.mock.domain.component.ExportDialectResult;

/**
 * Dialect export atomic service , M-e export phase 1 — the
 * "export first" decision). Reads one space's full rule set through the
 * management plane and renders it into a third-party dialect file
 * (Mockoon / WireMock) that the target tool can load as-is.
 * <p>Export is deliberately NOT publication-gated: migrating draft rules out
 * of testPike loses nothing, while the traffic-plane snapshot stays
 * publication-gated. Unsupported dialect features never vanish silently —
 * they surface in {@link ExportDialectResult#notes}.
 */
public interface MockDialectExportService {

    /**
     * Render one space into the requested dialect.
     *
     * @param spaceId target space (unknown id refused in plain words)
     * @param format  dialect id: {@code mockoon} or {@code wiremock}
     *                (anything else refused, naming both supported dialects)
     */
    ExportDialectResult export(long spaceId, String format);
}
