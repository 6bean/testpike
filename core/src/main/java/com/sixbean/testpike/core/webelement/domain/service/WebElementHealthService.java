package com.sixbean.testpike.core.webelement.domain.service;

import com.sixbean.testpike.core.webelement.api.dto.WebElementHealthView;

import java.util.Collection;
import java.util.Map;

/**
 * Step-health account for the Page Element Library (WE S7 8.3). All reads are
 * pure SQL windows over tp_web_element_health_daily — zero model calls, zero
 * write amplification beyond the drift alert itself. The miss rate merges
 * viewport buckets per element by design (the columnar split is kept on the
 * stat/confidence plane, not on the health plane).
 */
public interface WebElementHealthService {

    /**
     * Rolling health reading per element for the ledger list. Elements with
     * no anchor outcome inside the window horizon come back as unreached
     * (badge {@code none}, never alerted) — silent pages must not be dressed
     * up as sick ones.
     */
    Map<Long, WebElementHealthView> healthFor(Collection<Long> elementIds);

    /**
     * Days the daily rollup keeps before the relay hook prunes it:
     * the three drift windows plus one window of slack (window-days * 4).
     */
    int retentionDays();

    /**
     * Drift episode check for one element whose stats were just written:
     * when the miss rate climbed across two consecutive windows without a
     * total failure, appends one element-ledger audit event and files the
     * "about to break" station notice for the project owners. Same-day
     * re-triggers are suppressed by the notification dedup key, so a busy
     * relay cannot spam the bell. Never throws into the relay chain.
     *
     * @return number of station notices newly filed (0 when nothing fired)
     */
    int evaluateDrift(long elementId);
}
