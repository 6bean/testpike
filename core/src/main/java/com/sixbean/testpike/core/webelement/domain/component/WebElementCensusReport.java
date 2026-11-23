package com.sixbean.testpike.core.webelement.domain.component;

import java.util.List;

/**
 * Census totals (WE-S6): entries ranked by migration value plus honest
 * bookkeeping — scanned documents, unparseable ones and inline anchors that
 * could not be attributed to any resolvable page (templated goto urls).
 */
public record WebElementCensusReport(int scannedScripts,
                                     int parseFailures,
                                     int unattributedSteps,
                                     List<WebElementCensusEntry> entries) {

    public WebElementCensusReport {
        entries = List.copyOf(entries);
    }
}
