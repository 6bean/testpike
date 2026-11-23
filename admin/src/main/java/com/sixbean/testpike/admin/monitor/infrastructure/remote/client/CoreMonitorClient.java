package com.sixbean.testpike.admin.monitor.infrastructure.remote.client;

import com.sixbean.testpike.admin.monitor.api.dto.MonitorCheckLogView;
import com.sixbean.testpike.admin.monitor.api.dto.MonitorEpisodeView;
import com.sixbean.testpike.admin.monitor.api.dto.MonitorLinkedAssetsView;
import com.sixbean.testpike.admin.monitor.api.dto.MonitorTargetView;
import com.sixbean.testpike.admin.monitor.api.dto.SilenceRequest;
import com.sixbean.testpike.admin.monitor.api.dto.TargetSaveRequest;

import java.util.List;


public interface CoreMonitorClient {

    
    long createTarget(TargetSaveRequest request);

    
    void updateTarget(long id, TargetSaveRequest request);

    
    MonitorTargetView findTarget(long id);

    
    List<MonitorTargetView> listTargets(long projectId);

    /** Paged target list (wall page server-side paging channel; total travels with the window; wall filters pass through). */
    com.sixbean.testpike.admin.monitor.api.dto.MonitorTargetPageView pageTargets(
            long projectId, int page, int pageSize, String sortBy, String sortDir,
            String status, String targetType, String keyword);

    
    void updateEnabled(long id, boolean enabled);

    
    void deleteTarget(long id);

    
    void silence(long id, SilenceRequest request);

    
    void clearSilence(long id);

    
    List<MonitorCheckLogView> checkLogs(long targetId, int limit);

    
    MonitorEpisodeView openEpisode(long envId);

    
    MonitorLinkedAssetsView linkedAssets(long envId);
}
