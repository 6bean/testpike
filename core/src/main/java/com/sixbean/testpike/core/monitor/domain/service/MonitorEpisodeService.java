package com.sixbean.testpike.core.monitor.domain.service;

import com.sixbean.testpike.core.monitor.api.dto.MonitorEpisodeView;

import java.time.LocalDateTime;
import java.util.List;


public interface MonitorEpisodeService {

    
    String evaluate(long targetId);

    
    void markScanHeartbeat();

    
    int alertIfStalled(int graceSeconds);

    
    String configValue(String key);

    
    List<MonitorEpisodeView> findEpisodesOverlappingWindow(long envId, LocalDateTime from, LocalDateTime to);

    
    MonitorEpisodeView findOpenEpisodeByEnv(long envId);
}
