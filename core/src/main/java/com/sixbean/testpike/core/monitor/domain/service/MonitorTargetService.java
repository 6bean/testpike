package com.sixbean.testpike.core.monitor.domain.service;

import com.sixbean.testpike.core.monitor.api.dto.MonitorCheckLogView;
import com.sixbean.testpike.core.monitor.api.dto.MonitorTargetInput;
import com.sixbean.testpike.core.monitor.api.dto.MonitorTargetInfo;
import com.sixbean.testpike.core.monitor.api.dto.MonitorTargetPageView;

import java.util.List;


public interface MonitorTargetService {

    
    int MAX_CHECK_LOG_LIMIT = 200;

    
    long create(long projectId, MonitorTargetInput input);

    
    void update(long id, MonitorTargetInput input);

    
    MonitorTargetInfo get(long id);

    
    List<MonitorTargetInfo> list(long projectId);

    /** Paged-list hard window cap (same ceiling as {@link #MAX_CHECK_LOG_LIMIT}; oversize requests clamp down). */
    int MAX_PAGE_SIZE = 200;

    /**
     * Project-scoped paged target list — the server-side paging channel of the wall page
     * (S-group contract: list endpoints consumed at scale carry page/pageSize).
     * <p>{@code page} is 1-based; {@code pageSize} below 1 is rejected, above
     * {@link #MAX_PAGE_SIZE} clamps to the cap. {@code sortBy} runs through the strict
     * sort-column whitelist (injected/unknown names rejected with a 400 naming the
     * whitelist; same precedent as the server-side sort batch); {@code sortDir} only
     * honors "desc", anything else is ascending. Default order = id ascending.
     * {@link #list(long)} delegates to the same query at the whole window (zero drift).
     */
    MonitorTargetPageView page(long projectId, int page, int pageSize, String sortBy, String sortDir);

    /**
     * Same paged channel with server-side wall filters (what-you-see-is-what-you-filter).
     * <p>{@code status} mirrors the tile precedence exactly: DISABLED (enabled=false),
     * SILENCED (enabled, silence window open — expired silence still shows silenced
     * until the scheduler clears it), UP/DOWN/FLAPPING/RECOVERING (enabled, not
     * silenced, that probe state), UNKNOWN (enabled, not silenced, never probed into a
     * state). Case-insensitive; unknown values rejected with a 400 naming the choices.
     * {@code targetType} runs the save-side whitelist (HTTP/LOGIN/ONLINE/DB/CERT, same
     * rejection wording). {@code keyword} is a trimmed case-insensitive name contains —
     * user-typed % and _ stay literal. Count and window share one WHERE, so {@code total}
     * is always the whole filtered count. Blank/null filters are absent (no-op).
     */
    MonitorTargetPageView page(long projectId, int page, int pageSize, String sortBy, String sortDir,
                               String status, String targetType, String keyword);

    
    void updateEnabled(long id, boolean enabled);

    
    void delete(long id);

    
    void silence(long id, int minutes, String reason);

    
    void clearSilence(long id);

    
    List<MonitorCheckLogView> recentCheckLogs(long targetId, int limit);

    
    int scanDueTargets();

    
    void recordProbeFailure(long id, String cause);

    
    int resetStaleSnapshots();

    
    int intervalSecondsForLevel(int level);

    
    String configValue(String key);
}
