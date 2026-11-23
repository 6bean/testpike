package com.sixbean.testpike.core.mock.domain.service.impl;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.mock.api.dto.MockSpaceInfo;
import com.sixbean.testpike.core.mock.api.dto.MockStubInfo;
import com.sixbean.testpike.core.mock.domain.component.ExportDialectResult;
import com.sixbean.testpike.core.mock.domain.component.MockoonDialectRenderer;
import com.sixbean.testpike.core.mock.domain.component.WireMockDialectRenderer;
import com.sixbean.testpike.core.mock.domain.service.MockDialectExportService;
import com.sixbean.testpike.core.mock.domain.service.MockSpaceService;
import com.sixbean.testpike.core.mock.domain.service.MockStubService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * Dialect export service : reads the space header and its full
 * rule set through the existing atomic services (single read source — the
 * same rows the editor and snapshot serve, never a copy) and hands them to
 * the renderer chosen by format. Unknown format and ghost space are refused
 * here in plain words so every door (core internal, api CI, admin bridge)
 * speaks one spectrum without repeating the checks.
 */
@Service
public class MockDialectExportServiceImpl implements MockDialectExportService {

    private final MockSpaceService spaceService;
    private final MockStubService stubService;
    private final MockoonDialectRenderer mockoonRenderer;
    private final WireMockDialectRenderer wireMockRenderer;

    public MockDialectExportServiceImpl(MockSpaceService spaceService, MockStubService stubService,
                                        MockoonDialectRenderer mockoonRenderer,
                                        WireMockDialectRenderer wireMockRenderer) {
        this.spaceService = spaceService;
        this.stubService = stubService;
        this.mockoonRenderer = mockoonRenderer;
        this.wireMockRenderer = wireMockRenderer;
    }

    @Override
    public ExportDialectResult export(long spaceId, String format) {
        // Ghost space: MockSpaceService.get refuses in plain words — export
        // must never answer an empty document for a space that does not exist.
        MockSpaceInfo space = spaceService.get(spaceId);
        String dialect = format == null ? "" : format.trim().toLowerCase(Locale.ROOT);
        if (MockoonDialectRenderer.FORMAT.equals(dialect)) {
            return mockoonRenderer.renderResult(space, rulesOf(spaceId));
        }
        if (WireMockDialectRenderer.FORMAT.equals(dialect)) {
            return wireMockRenderer.renderResult(space, rulesOf(spaceId));
        }
        throw new ServiceException("导出方言「" + format + "」不支持，可选值：mockoon（Mockoon 文档）"
                + " 或 wiremock（WireMock stub mappings）", BizErrorCode.VALIDATION_ERROR);
    }

    /**
     * Full rule set of the space in evaluation order — including disabled
     * rows (they export as disabled, never dropped) and regardless of the
     * publication state (management-plane read, see the interface contract).
     */
    private List<MockStubInfo> rulesOf(long spaceId) {
        return stubService.listBySpace(spaceId);
    }
}
